package studio.liuye.mobinteract.backpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/** 按权重抽取的纯工具类（不依赖任何 Bukkit 类型，便于单元测试）。 */
public final class WeightedSampler {

    private WeightedSampler() {
    }

    /**
     * 按权重随机挑一个元素。
     *
     * @return 抽中的元素；候选为空或总权重不为正数时返回 {@code null}
     */
    public static <T> T pick(List<T> items, ToDoubleFunction<T> weight, Random random) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        double total = 0.0D;
        for (T item : items) {
            double value = weight.applyAsDouble(item);
            if (value > 0.0D && !Double.isNaN(value)) {
                total += value;
            }
        }
        if (total <= 0.0D) {
            return null;
        }
        double roll = random.nextDouble() * total;
        double cursor = 0.0D;
        T last = null;
        for (T item : items) {
            double value = weight.applyAsDouble(item);
            if (value <= 0.0D || Double.isNaN(value)) {
                continue;
            }
            last = item;
            cursor += value;
            if (roll < cursor) {
                return item;
            }
        }
        return last;
    }

    /**
     * 按权重“无放回”抽样 {@code count} 个不同元素。
     *
     * <p>权重为 0 或负数的元素恒不被抽中；候选不足时返回数量少于 {@code count}。</p>
     */
    public static <T> List<T> sampleWithoutReplacement(List<T> items, ToDoubleFunction<T> weight,
            int count, Random random) {
        List<T> pool = new ArrayList<>(items);
        List<T> picked = new ArrayList<>(Math.min(count, pool.size()));
        while (picked.size() < count && !pool.isEmpty()) {
            T chosen = pick(pool, weight, random);
            if (chosen == null) {
                break;
            }
            picked.add(chosen);
            pool.remove(chosen);
        }
        return picked;
    }
}
