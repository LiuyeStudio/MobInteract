package studio.liuye.mobinteract.update;

import java.util.ArrayList;
import java.util.List;

/**
 * Release 标签与插件版本的比较。
 *
 * <p>不引入任何版本库依赖：把 {@code v1.3.0} 这类标签归一化成
 * {@code 1.3.0}，再按 {@code . - _ +} 切成若干段逐段比较。
 * 纯数字段按数值比（{@code 1.10 > 1.9}），数字段永远大于非数字段，
 * 因此 {@code 1.3.0 > 1.3.0-beta}，与常见的语义化版本习惯一致。</p>
 *
 * <p>纯函数、不碰 Bukkit，单测直接调。</p>
 */
public final class VersionTags {

    /** 段之间的分隔符：点、短横、下划线、加号以及空白。 */
    private static final String SEPARATORS = ".-_+ ";

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
        List<String> a = split(left);
        List<String> b = split(right);
        int size = Math.max(a.size(), b.size());
        for (int index = 0; index < size; index++) {
            // 缺失的段按 0 处理：1.3 与 1.3.0 视为同一个版本。
            String partA = index < a.size() ? a.get(index) : "0";
            String partB = index < b.size() ? b.get(index) : "0";
            int result = comparePart(partA, partB);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /** {@code candidate} 是否比 {@code current} 新。 */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static List<String> split(String version) {
        String value = normalize(version);
        List<String> parts = new ArrayList<>(4);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
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
        if (parts.isEmpty()) {
            parts.add("0");
        }
        return parts;
    }

    private static int comparePart(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return compareNumbers(left, right);
        }
        if (leftNumeric) {
            // 正式版（1.3.0）比预发布版（1.3.0-beta）新。
            return 1;
        }
        if (rightNumeric) {
            return -1;
        }
        return left.compareToIgnoreCase(right);
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
}
