package studio.liuye.mobinteract.backpack;

import java.util.List;
import java.util.Random;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.config.PlacementSettings;

/** 按配置为一个怪物生成随机方块背包。 */
public final class BackpackFactory {

    private BackpackFactory() {
    }

    /**
     * 一个空背包（还没有任何条目）。
     *
     * <p>用于“怪物还没掷到背包、但在地图上捡到了方块”的情况：
     * 捡到之后再往里放，行为与掷到背包的怪物一致。</p>
     */
    public static MobBackpack empty() {
        return new MobBackpack();
    }

    /**
     * 生成一个背包：
     * <ol>
     *   <li>按 kinds 范围随机决定种类数；</li>
     *   <li>从条目目录中按 weight 无放回抽样出这些种类；</li>
     *   <li>按 total 范围随机决定总数量；</li>
     *   <li>把总数量随机分配到已选种类，每种至少 1，总数不足时截断种类数。</li>
     * </ol>
     *
     * @return 新背包；条目目录为空时返回空背包
     */
    public static MobBackpack create(PlacementSettings settings, Random random) {
        MobBackpack backpack = new MobBackpack();
        List<PlacementEntry> catalog = settings.entries();
        if (catalog.isEmpty()) {
            return backpack;
        }

        int kinds = randomInRange(settings.backpackKindsMin(), settings.backpackKindsMax(), random);
        if (kinds <= 0) {
            return backpack;
        }
        List<PlacementEntry> chosen = WeightedSampler.sampleWithoutReplacement(
                catalog, PlacementEntry::weight, Math.min(kinds, catalog.size()), random);
        if (chosen.isEmpty()) {
            return backpack;
        }

        int total = randomInRange(settings.backpackTotalMin(), settings.backpackTotalMax(), random);
        int[] counts = BackpackDistribution.distribute(total, chosen.size(), random);
        for (int index = 0; index < counts.length; index++) {
            backpack.put(chosen.get(index), counts[index]);
        }
        return backpack;
    }

    private static int randomInRange(int min, int max, Random random) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        if (high <= low) {
            return low;
        }
        return low + random.nextInt(high - low + 1);
    }
}
