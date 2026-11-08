package studio.liuye.mobinteract.message;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * {@code message.yml}：所有发给玩家的文本。
 *
 * <p>文本里的颜色码用 {@code &}（如 {@code &7}），读取时转成 {@code §}；
 * 占位符写成 {@code {名字}}，由 {@link #get(String, Object...)} /
 * {@link #getList(String, Object...)} 成对传入后用 {@link String#replace} 替换
 * （因此文本里可以直接写 {@code %}，不会走 {@code String.format}）。</p>
 *
 * <p>加载方式与 {@code ConfigManager} 一致：{@link #saveDefaultsIfMissing()} 把 jar 内的
 * 默认文件复制到 data 目录，{@link #reload()} 重新解析并产出不可变快照，
 * 通过 {@code volatile} 引用整体替换——区域线程读到的永远是一份自洽的文本表。
 * 缺失的键回退到 jar 内置默认值，仍然缺失时返回 {@code <missing:key>} 并在控制台告警一次。
 * 重载会做 IO，只应在启用 / 命令线程上调用。</p>
 */
public final class Messages {

    public static final String MESSAGE_FILE = "message.yml";

    /** 允许出现在 {@code &} 后面的颜色 / 格式代码字符（小写），避免误伤正文里的裸 {@code &}。 */
    private static final String COLOR_CODES = "0123456789abcdefklmnor";

    private static final char SECTION = '§';

    private final Plugin plugin;

    /** 已经告警过的缺失键：每个键只刷一次控制台。 */
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    /** 当前生效的文本快照（整体替换，不在原地改）。 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** jar 内置默认值快照，懒加载一次。 */
    private volatile Snapshot bundledSnapshot;

    public Messages(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 首次启动时把 jar 内的默认 {@code message.yml} 写到 data 目录。 */
    public void saveDefaultsIfMissing() {
        File file = new File(plugin.getDataFolder(), MESSAGE_FILE);
        if (!file.exists()) {
            plugin.saveResource(MESSAGE_FILE, false);
        }
    }

    /**
     * 重新读取 {@code message.yml}。
     *
     * <p>做文件 IO，只应在插件启用或 {@code /mobinteract reload}（全局区域线程）时调用。</p>
     */
    public void reload() {
        File file = new File(plugin.getDataFolder(), MESSAGE_FILE);
        if (!file.exists()) {
            plugin.saveResource(MESSAGE_FILE, false);
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("读取 " + MESSAGE_FILE + " 失败，使用内置默认值：" + exception.getMessage());
            configuration = loadBundled();
        }
        this.snapshot = Snapshot.of(configuration);
        // 重载后允许重新告警：用户可能又删掉了刚修好的键。
        warnedKeys.clear();
    }

    /**
     * 取一条单行消息。
     *
     * @param key          消息键，如 {@code command.status.header}
     * @param placeholders 成对的占位符名与值，如 {@code "count", 3}
     * @return 已替换占位符、已转换颜色码的文本；缺失时为 {@code <missing:key>}
     */
    public String get(String key, Object... placeholders) {
        if (key == null) {
            return missing("<null>");
        }
        String value = snapshot.string(key);
        if (value == null) {
            value = bundled().string(key);
        }
        if (value == null) {
            return missing(key);
        }
        return apply(value, placeholders);
    }

    /**
     * 取一条消息并按行返回：文件里写成 YAML 列表就是多行，写成单个字符串就是一行。
     *
     * @param key          消息键
     * @param placeholders 成对的占位符名与值
     * @return 至少一条文本；缺失时的唯一元素为 {@code <missing:key>}
     */
    public List<String> getList(String key, Object... placeholders) {
        if (key == null) {
            return List.of(missing("<null>"));
        }
        List<String> list = snapshot.list(key);
        if (list == null) {
            list = bundled().list(key);
        }
        if (list != null) {
            List<String> lines = new ArrayList<>(list.size());
            for (String line : list) {
                lines.add(apply(line, placeholders));
            }
            return List.copyOf(lines);
        }
        String single = snapshot.string(key);
        if (single == null) {
            single = bundled().string(key);
        }
        if (single == null) {
            return List.of(missing(key));
        }
        return List.of(apply(single, placeholders));
    }

    /** 该键是否可用（当前文件或 jar 内置默认值里有）。 */
    public boolean has(String key) {
        if (key == null) {
            return false;
        }
        return snapshot.has(key) || bundled().has(key);
    }

    /** 当前生效的消息键数量（含列表型键），用于 {@code /mobinteract reload} 回显。 */
    public int size() {
        return snapshot.size();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Snapshot bundled() {
        Snapshot cached = bundledSnapshot;
        if (cached == null) {
            cached = Snapshot.of(loadBundled());
            bundledSnapshot = cached;
        }
        return cached;
    }

    private YamlConfiguration loadBundled() {
        YamlConfiguration configuration = new YamlConfiguration();
        try (var stream = plugin.getResource(MESSAGE_FILE)) {
            if (stream == null) {
                return configuration;
            }
            configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("读取 jar 内置 " + MESSAGE_FILE + " 失败：" + exception.getMessage());
        }
        return configuration;
    }

    private String missing(String key) {
        if (warnedKeys.add(key)) {
            plugin.getLogger().warning(MESSAGE_FILE + " 缺少消息键 " + key + "（已回退到内置默认值）");
        }
        return "<missing:" + key + ">";
    }

    /** 成对替换占位符；不成对的尾部参数会被忽略。 */
    private static String apply(String text, Object... placeholders) {
        if (text == null || placeholders == null || placeholders.length < 2) {
            return text;
        }
        String result = text;
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            Object name = placeholders[index];
            if (name == null) {
                continue;
            }
            result = result.replace("{" + name + "}", String.valueOf(placeholders[index + 1]));
        }
        return result;
    }

    /** 把 {@code &} 颜色码转成 {@code §}（只转换后面跟合法代码字符的情况）。 */
    private static String colorize(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '&' && index + 1 < text.length()
                    && COLOR_CODES.indexOf(text.charAt(index + 1)) >= 0) {
                builder.append(SECTION);
                continue;
            }
            builder.append(current);
        }
        return builder.toString();
    }

    /** 一次重载得到的不可变文本表：字符串键与列表键分开存放。 */
    private record Snapshot(Map<String, String> strings, Map<String, List<String>> lists) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());

        static Snapshot of(YamlConfiguration configuration) {
            if (configuration == null) {
                return EMPTY;
            }
            Map<String, String> strings = new LinkedHashMap<>();
            Map<String, List<String>> lists = new LinkedHashMap<>();
            for (String key : configuration.getKeys(true)) {
                if (configuration.isConfigurationSection(key)) {
                    continue;
                }
                if (configuration.isList(key)) {
                    List<String> lines = new ArrayList<>();
                    for (Object raw : configuration.getList(key, List.of())) {
                        if (raw != null) {
                            lines.add(colorize(String.valueOf(raw)));
                        }
                    }
                    lists.put(key, List.copyOf(lines));
                    continue;
                }
                String raw = configuration.getString(key);
                if (raw != null) {
                    strings.put(key, colorize(raw));
                }
            }
            return new Snapshot(Collections.unmodifiableMap(strings), Collections.unmodifiableMap(lists));
        }

        String string(String key) {
            return strings.get(key);
        }

        List<String> list(String key) {
            return lists.get(key);
        }

        boolean has(String key) {
            return strings.containsKey(key) || lists.containsKey(key);
        }

        int size() {
            return strings.size() + lists.size();
        }
    }
}
