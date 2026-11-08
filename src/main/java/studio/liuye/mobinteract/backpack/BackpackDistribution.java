package studio.liuye.mobinteract.backpack;

import java.util.Arrays;
import java.util.Random;

/** 背包数量分配的纯逻辑（不依赖 Bukkit，便于单元测试）。 */
public final class BackpackDistribution {

    private BackpackDistribution() {
    }

    /**
     * 把 {@code total} 个方块分配到若干种类上，每种至少 1 个。
     *
     * <p>若 {@code total < kinds}，则种类数被截断为 {@code total}（多出的种类不会被分配），
     * 返回数组长度即实际可用种类数。</p>
     *
     * @param total  总数量（&lt; 0 按 0 处理）
     * @param kinds  期望种类数
     * @param random 随机源
     * @return 每个种类分到的数量，索引与“已选条目列表”一一对应
     */
    public static int[] distribute(int total, int kinds, Random random) {
        int safeTotal = Math.max(total, 0);
        int effectiveKinds = Math.min(Math.max(kinds, 0), safeTotal);
        if (effectiveKinds == 0) {
            return new int[0];
        }
        int[] counts = new int[effectiveKinds];
        Arrays.fill(counts, 1);
        int remaining = safeTotal - effectiveKinds;
        for (int i = 0; i < remaining; i++) {
            counts[random.nextInt(effectiveKinds)]++;
        }
        return counts;
    }
}
