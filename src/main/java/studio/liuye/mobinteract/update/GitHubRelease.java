package studio.liuye.mobinteract.update;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub Releases 接口返回体的最小投影。
 *
 * <p>插件不引入 JSON 库（避免多一个要 shade 的运行期依赖），这里只做
 * “把返回体切成一个个对象，再挑出需要的几个字段”这件事：</p>
 * <ul>
 *   <li>{@link #objects(String)} 按花括号深度切分顶层数组里的每个对象，
 *       字符串里的花括号与转义引号都不算数；</li>
 *   <li>{@link #string(String, String)} / {@link #bool(String, String)} 在单个对象里
 *       取字段值，支持反斜杠转义（引号、反斜杠、换行，以及 u 开头的四位十六进制转义）。</li>
 * </ul>
 *
 * <p>纯函数、不碰 Bukkit，单测直接调。</p>
 */
public record GitHubRelease(String tag, String url, String name, boolean prerelease) {

    /**
     * 从 {@code /repos/{owner}/{repo}/releases} 的返回体里挑出第一条可用的版本。
     *
     * <p>按 GitHub 的默认顺序（发布时间倒序）遍历：跳过草稿；
     * {@code includePrerelease} 为 false 时跳过预发布版。</p>
     *
     * @return 挑不出可用版本时返回 {@code null}
     */
    public static GitHubRelease pick(String json, boolean includePrerelease) {
        for (String object : objects(json)) {
            String tag = string(object, "tag_name");
            if (tag == null || tag.isBlank()) {
                continue;
            }
            if (bool(object, "draft")) {
                continue;
            }
            boolean prerelease = bool(object, "prerelease");
            if (prerelease && !includePrerelease) {
                continue;
            }
            String url = string(object, "html_url");
            String name = string(object, "name");
            return new GitHubRelease(tag.trim(), url == null ? "" : url, name == null ? "" : name, prerelease);
        }
        return null;
    }

    /** 把返回体按顶层数组切分成若干对象文本；不是数组时也会退化成“找到一个算一个”。 */
    public static List<String> objects(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}') {
                if (depth > 0) {
                    depth--;
                }
                if (depth == 0 && start >= 0) {
                    result.add(json.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return result;
    }

    /** 取字符串字段；字段不存在或不是字符串时返回 {@code null}。 */
    public static String string(String object, String key) {
        int valueStart = valueStart(object, key);
        if (valueStart < 0 || valueStart >= object.length() || object.charAt(valueStart) != '"') {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        int index = valueStart + 1;
        while (index < object.length()) {
            char current = object.charAt(index++);
            if (current == '"') {
                return builder.toString();
            }
            if (current != '\\') {
                builder.append(current);
                continue;
            }
            if (index >= object.length()) {
                break;
            }
            char escape = object.charAt(index++);
            switch (escape) {
                case 'n' -> builder.append('\n');
                case 't' -> builder.append('\t');
                case 'r' -> builder.append('\r');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'u' -> {
                    if (index + 4 <= object.length()) {
                        try {
                            builder.append((char) Integer.parseInt(object.substring(index, index + 4), 16));
                        } catch (NumberFormatException ignored) {
                            // 转义本身非法时忽略这一段，保持解析不中断。
                        }
                        index += 4;
                    }
                }
                default -> builder.append(escape);
            }
        }
        return null;
    }

    /** 取布尔字段；字段不存在或不是布尔值时返回 {@code false}。 */
    public static boolean bool(String object, String key) {
        int valueStart = valueStart(object, key);
        if (valueStart < 0) {
            return false;
        }
        return object.startsWith("true", valueStart);
    }

    /**
     * 定位 {@code "key":} 之后第一个非空白字符的下标；找不到时返回 -1。
     *
     * <p>只认<b>本层</b>的键：Release 对象里还嵌着 {@code author}、{@code assets}
     * 等子对象，它们也有 {@code html_url} / {@code name} 这类同名字段，
     * 直接用 {@code indexOf} 会错拿成作者主页。这里按花括号 / 方括号深度遍历，
     * 只在本层（深度 1）匹配键名。</p>
     */
    private static int valueStart(String object, String key) {
        if (object == null || key == null) {
            return -1;
        }
        int objectDepth = 0;
        int arrayDepth = 0;
        int index = 0;
        while (index < object.length()) {
            char current = object.charAt(index);
            if (current == '"') {
                int stringEnd = skipString(object, index);
                if (stringEnd < 0) {
                    return -1;
                }
                String literal = object.substring(index + 1, stringEnd - 1);
                int cursor = stringEnd;
                while (cursor < object.length() && Character.isWhitespace(object.charAt(cursor))) {
                    cursor++;
                }
                boolean isTopLevelKey = objectDepth == 1 && arrayDepth == 0
                        && cursor < object.length() && object.charAt(cursor) == ':';
                if (isTopLevelKey && literal.equals(key)) {
                    cursor++;
                    while (cursor < object.length() && Character.isWhitespace(object.charAt(cursor))) {
                        cursor++;
                    }
                    return cursor < object.length() ? cursor : -1;
                }
                index = stringEnd;
                continue;
            }
            if (current == '{') {
                objectDepth++;
            } else if (current == '}') {
                objectDepth--;
            } else if (current == '[') {
                arrayDepth++;
            } else if (current == ']') {
                arrayDepth--;
            }
            index++;
        }
        return -1;
    }

    /** 跳过一个字符串字面量：{@code start} 指向开引号，返回结束引号之后的下标。 */
    private static int skipString(String text, int start) {
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            if (current == '"') {
                return index + 1;
            }
            index++;
        }
        return -1;
    }
}
