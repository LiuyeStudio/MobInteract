package studio.liuye.mobinteract.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Release 标签与插件版本的比较。
 *
 * <p>不引入任何版本库依赖。把 {@code v1.3.0}、{@code release-1.4.0} 这类标签归一化之后，
 * 拆成「主版本段」与「预发布段」两部分分别比较：</p>
 *
 * <ul>
 *   <li>主版本段（{@code 1.3.0} 的 {@code 1}/{@code 3}/{@code 0}）按<b>数值</b>比较，
 *       所以 {@code 1.10 > 1.9}；缺省段补 0，所以 {@code 1.3 == 1.3.0}；</li>
 *   <li>有预发布段的永远比没有的旧：{@code 1.3.0-beta < 1.3.0}；</li>
 *   <li>预发布段里的字母段按「字母前缀 + 尾随数字」比较，所以
 *       {@code rc10 > rc2}（旧实现直接比字符串，rc2 反而更大）；</li>
 *   <li>{@code +build} 这种构建元数据<b>整段忽略</b>（旧实现把 {@code +} 当分隔符，
 *       于是 {@code 1.4.0+build.7} 被当成比 {@code 1.4.0} 多出两段，永远“更新”）。</li>
 * </ul>
 *
 * <p>纯函数、不碰 Bukkit，单测直接调。</p>
 */
public final class VersionTags {

    /** 段之间的分隔符：点、短横、下划线以及空白。 */
    private static final String SEPARATORS = ".-_ ";

    private VersionTags() {
    }

    /** 去掉前缀 {@code v} / {@code V} 与首尾空白；{@code null} 视为空串。 */
    public static String normalize(String tag) {
        if (tag == null) {
            return "";
        }
        String value = tag.trim();
        while (!value.isEmpty() && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
            value = value.substring(1).trim();
        }
        return value;
    }

    /**
     * 比较两个版本串。
     *
     * @return 负数表示 {@code left} 更旧，0 表示相同，正数表示 {@code left} 更新
     */
    public static int compare(String left, String right) {
        Parsed a = Parsed.of(left);
        Parsed b = Parsed.of(right);

        int core = compareCore(a.core(), b.core());
        if (core != 0) {
            return core;
        }
        if (a.pre().isEmpty() && b.pre().isEmpty()) {
            return 0;
        }
        if (a.pre().isEmpty()) {
            // 没有预发布段 = 正式版，比任何 beta / rc 都新。
            return 1;
        }
        if (b.pre().isEmpty()) {
            return -1;
        }
        return comparePre(a.pre(), b.pre());
    }

    /** {@code candidate} 是否比 {@code current} 新。 */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /** 主版本段逐段比较，缺省补 0。 */
    private static int compareCore(List<String> left, List<String> right) {
        int size = Math.max(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            String partA = index < left.size() ? left.get(index) : "0";
            String partB = index < right.size() ? right.get(index) : "0";
            int result = compareNumericAware(partA, partB);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * 预发布段逐个比较。
     *
     * <p>字段少的更旧（{@code alpha < alpha.1}）；数字段比字母段旧
     * （{@code 1.0.0-1 < 1.0.0-alpha}，与语义化版本一致）。</p>
     */
    private static int comparePre(List<String> left, List<String> right) {
        int size = Math.min(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            int result = compareIdent(left.get(index), right.get(index));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareNumericAware(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return compareNumbers(left, right);
        }
        if (leftNumeric) {
            return 1;
        }
        if (rightNumeric) {
            return -1;
        }
        return compareIdent(left, right);
    }

    private static int compareIdent(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return compareNumbers(left, right);
        }
        if (leftNumeric != rightNumeric) {
            // 语义化版本：纯数字标识符的优先级低于字母标识符。
            return leftNumeric ? -1 : 1;
        }
        String prefixA = alphaPrefix(left);
        String prefixB = alphaPrefix(right);
        int prefix = prefixA.compareToIgnoreCase(prefixB);
        if (prefix != 0) {
            return prefix;
        }
        // 前缀相同再比尾随数字：rc10 > rc2（旧实现比整串，rc2 反而更大）。
        String numberA = left.substring(prefixA.length());
        String numberB = right.substring(prefixB.length());
        if (isNumeric(numberA) && isNumeric(numberB)) {
            return compareNumbers(numberA, numberB);
        }
        return left.compareToIgnoreCase(right);
    }

    /** 取出开头的连续字母部分（没有则为空串）。 */
    private static String alphaPrefix(String value) {
        int index = 0;
        while (index < value.length() && !Character.isDigit(value.charAt(index))) {
            index++;
        }
        return value.substring(0, index);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** 逐位比较数字串，避免 {@code Integer.parseInt} 在超长数字上溢出。 */
    private static int compareNumbers(String left, String right) {
        String a = trimLeadingZeros(left);
        String b = trimLeadingZeros(right);
        if (a.length() != b.length()) {
            return a.length() < b.length() ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static String trimLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    private static List<String> split(String version) {
        List<String> parts = new ArrayList<>(4);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < version.length(); index++) {
            char current = version.charAt(index);
            if (SEPARATORS.indexOf(current) >= 0) {
                if (builder.length() > 0) {
                    parts.add(builder.toString());
                    builder.setLength(0);
                }
                continue;
            }
            builder.append(current);
        }
        if (builder.length() > 0) {
            parts.add(builder.toString());
        }
        return parts;
    }

    /**
     * 拆分后的版本：主版本段 + 预发布段。
     *
     * @param core 主版本段（至少一段）
     * @param pre  预发布段（可为空）
     */
    private record Parsed(List<String> core, List<String> pre) {

        static Parsed of(String raw) {
            String value = normalize(raw);
            int plus = value.indexOf('+');
            if (plus >= 0) {
                // 构建元数据不参与比较：1.4.0+build.7 与 1.4.0 视为同一版本。
                value = value.substring(0, plus);
            }
            String corePart = value;
            String prePart = "";
            int dash = value.indexOf('-');
            if (dash >= 0) {
                corePart = value.substring(0, dash);
                prePart = value.substring(dash + 1);
            }
            List<String> core = split(corePart);
            List<String> pre = split(prePart);
            // "release-1.4.0" 这种：破折号前面只是一个单词前缀，真正的版本号在后面。
            if (!hasDigit(corePart) && !pre.isEmpty() && hasDigit(pre.get(0))) {
                core = pre;
                pre = List.of();
            }
            if (core.isEmpty()) {
                core = List.of("0");
            }
            return new Parsed(core, pre);
        }

        private static boolean hasDigit(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isDigit(value.charAt(index))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return core + "-" + pre;
        }
    }

    /** 仅用于日志：{@code Locale.ROOT} 下的可读形式，避免不同平台的大小写差异。 */
    static String describe(String version) {
        Parsed parsed = Parsed.of(version);
        return parsed.core().toString().toLowerCase(Locale.ROOT);
    }
}
