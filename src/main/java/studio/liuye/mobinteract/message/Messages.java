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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** 占位符：{@code {name}}，名字允许字母数字与 {@code . _ -}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)\\}");

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
        // 顺序很重要：先看用户文件里写的是列表还是单行，两者都看过之后才回退内置默认值。
        // 旧实现先拿内置列表，于是服主把某个多行提示改成一行时，改写会被直接忽略——
        // 明明文件里改了、游戏里却还是老样子，这种“改了没用”最难排查。
        List<String> list = snapshot.list(key);
        if (list != null) {
            return applyAll(list, placeholders);
        }
        String single = snapshot.string(key);
        if (single != null) {
            return List.of(apply(single, placeholders));
        }
        list = bundled().list(key);
        if (list != null) {
            return applyAll(list, placeholders);
        }
        single = bundled().string(key);
        if (single == null) {
            return List.of(missing(key));
        }
        return List.of(apply(single, placeholders));
    }

    private static List<String> applyAll(List<String> list, Object... placeholders) {
        List<String> lines = new ArrayList<>(list.size());
        for (String line : list) {
            lines.add(apply(line, placeholders));
        }
        return List.copyOf(lines);
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

    /**
     * 成对替换占位符；不成对的尾部参数会被忽略。
     *
     * <p><b>一次扫描</b>，不是“逐对 replace 链”：链式替换里如果某个值是 {@code "{url}"}
     * 这种内容（比如玩家名、URL 恰好带了花括号），它会被后面的某一对再替换一次，
     * 于是文本里凭空出现别的字段。这里先把成对的键值收进表，再对原文走一遍正则替换，
     * 替换出来的内容不会再被扫描。</p>
     */
    private static String apply(String text, Object... placeholders) {
        if (text == null || placeholders == null || placeholders.length < 2 || text.indexOf('{') < 0) {
            return text;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            Object name = placeholders[index];
            if (name == null) {
                continue;
            }
            values.put(String.valueOf(name), String.valueOf(placeholders[index + 1]));
        }
        if (values.isEmpty()) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder builder = new StringBuilder(text.length());
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            matcher.appendReplacement(builder,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(builder);
        return builder.toString();
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
                    && COLOR_CODES.indexOf(Character.toLowerCase(text.charAt(index + 1))) >= 0) {
                // 大小写都认：服主写 &A 跟 &a 是同一个颜色，
                // 旧实现只认小写，于是 &A / &R 会原样糊在游戏里。
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
