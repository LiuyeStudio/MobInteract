package studio.liuye.mobinteract.backpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import studio.liuye.mobinteract.config.PlacementEntry;

/**
 * 单个怪物的轻量“方块背包”。
 *
 * <p>不是真正的容器界面，只是“条目 → 剩余数量”的映射。访问集中在实体线程上，
 * 但读取方法也做了防御性拷贝，便于命令线程只读展示。</p>
 */
public final class MobBackpack {

    private final Map<PlacementEntry, Integer> stock = new LinkedHashMap<>();

    MobBackpack() {
    }

    synchronized void put(PlacementEntry entry, int amount) {
        if (amount <= 0) {
            return;
        }
        stock.merge(entry, amount, Integer::sum);
    }

    /**
     * 往背包里加东西（拾取地上的方块时使用）。
     *
     * <p>同一个条目（记录相等）会累加到已有数量上，因此反复捡同一种方块不会长出一堆重复条目。</p>
     *
     * @return 加入后的持有数量
     */
    public synchronized int add(PlacementEntry entry, int amount) {
        if (entry == null || amount <= 0) {
            return 0;
        }
        put(entry, amount);
        return amountOf(entry);
    }

    public synchronized boolean isEmpty() {
        return stock.isEmpty();
    }

    public synchronized int total() {
        int total = 0;
        for (int amount : stock.values()) {
            total += amount;
        }
        return total;
    }

    public synchronized int kinds() {
        return stock.size();
    }

    public synchronized int amountOf(PlacementEntry entry) {
        return stock.getOrDefault(entry, 0);
    }

    /** 只读快照，供命令展示。 */
    public synchronized Map<PlacementEntry, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(stock));
    }

    /** 当前剩余条目列表。 */
    public synchronized List<PlacementEntry> entries() {
        return new ArrayList<>(stock.keySet());
    }

    /** 从剩余条目中按条目权重重新抽样，可能返回 {@code null}（背包为空）。 */
    public synchronized PlacementEntry pick(Random random) {
        List<PlacementEntry> candidates = entries();
        if (candidates.isEmpty()) {
            return null;
        }
        return WeightedSampler.pick(candidates, PlacementEntry::weight, random);
    }

    /** 消耗一个条目；耗尽后自动从背包移除。 */
    public synchronized void consume(PlacementEntry entry) {
        if (entry == null) {
            return;
        }
        stock.computeIfPresent(entry, (key, amount) -> amount > 1 ? amount - 1 : null);
    }

    /**
     * 预留一个条目：有库存就立刻扣除并返回 {@code true}。
     *
     * <p>放置是异步的（要跳到目标位置所在区域），如果等放置成功再扣，
     * 期间目标重新激活可能又抽到同一个条目，造成“背包已经空了却还在放”。
     * 因此改成选中时就扣除，失败再通过 {@link #giveBack(PlacementEntry)} 退回。</p>
     */
    public synchronized boolean take(PlacementEntry entry) {
        if (entry == null) {
            return false;
        }
        Integer amount = stock.get(entry);
        if (amount == null || amount <= 0) {
            return false;
        }
        if (amount > 1) {
            stock.put(entry, amount - 1);
        } else {
            stock.remove(entry);
        }
        return true;
    }

    /** 退回一个条目（放置失败时使用）。 */
    public synchronized void giveBack(PlacementEntry entry) {
        if (entry == null) {
            return;
        }
        put(entry, 1);
    }
}
