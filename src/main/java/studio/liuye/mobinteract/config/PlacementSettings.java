package studio.liuye.mobinteract.config;

import java.util.List;

/**
 * placement.yml 解析后的不可变快照。
 *
 * @param enabled            总开关
 * @param mobFilter          生物筛选规则（白名单 / 黑名单 / 全部生物）
 * @param worldFilter        世界筛选规则（留空 = 所有世界）
 * @param backpackChance     拥有背包的概率
 * @param backpackTotalMin   背包总数量下限
 * @param backpackTotalMax   背包总数量上限
 * @param backpackKindsMin   背包种类数下限
 * @param backpackKindsMax   背包种类数上限
 * @param entries            可放置条目目录
 * @param maxDistance        最大放置距离
 * @param maxPerChase        单次追击最大放置次数
 * @param cooldownMillis     放置冷却（毫秒）
 * @param buildDelayTicks    短搭建进度（tick）
 * @param verticalDrop       判定垂直落差的最小高度差
 * @param blockLifetimeSeconds 放置方块存在时间（秒），0 = 永久
 * @param stairUp            横向距离很远时是否朝目标搭楼梯（每级同时前进一格、抬高一级）
 * @param stairMinDistance   横向距离 ≥ 该值时优先搭楼梯
 * @param pillarUp           高处目标是否先找空列垫方块上升（垫高到目标高度）
 * @param pillarUpMaxHeight  单次追击内竖直垫高的最大格数
 * @param pillarFarHeight    高度差 ≥ 该值时一律垫高（不搭楼梯）
 * @param pillarNearDistance 横向距离 ≤ 该值时一律原地垫高（楼梯会冲过头）
 * @param bridge             是否允许朝目标横向搭桥
 * @param bridgeMaxLength    离目标超过这么多格就不再搭桥（防止天桥）
 * @param laneSearchRadius   挑选“垫高列”时的水平搜索半径（格）
 * @param requireAdjacent    是否要求放置位置必须紧挨着怪物（禁止隔空放置）
 * @param maxFacingAngle     放置前怪物朝向与目标方向的最大夹角（度）
 * @param verticalAggro      目标在自己上方但没有索敌时，是否主动锁定上方可攻击的玩家
 * @param specialOnlyUnderTarget 功能方块（TNT / 水 / 熔岩桶）是否只放在目标脚下
 * @param spreadCrowd        带方块的怪物挤在一起时是否主动让位
 * @param spacingRadius      判定“挤在一起”的距离（格）
 * @param smartAscend        上升前先侦察原版寻路与附近台阶，必要时才搭方块
 * @param retryCooldownMillis 放置失败 / 被拒绝后的重试冷却（毫秒）
 * @param pickupEnabled      是否主动拾取地上的物品
 * @param pickupRadius       搜索掉落物的半径（格）
 * @param pickupReach        走到这个距离内就真正捡起来（格）
 * @param pickupBlocks       是否拾取可放置方块（进方块背包）
 * @param pickupPickaxes     是否拾取镐子（主手空着时换上）
 * @param pickupOnlyEquipped 只有通过 {@code equip.yml} 闸门的怪物才允许拾取；
 *                           保证“带镐子 + 带方块”的比例加起来不超过闸门值
 */
public record PlacementSettings(
        boolean enabled,
        MobFilter mobFilter,
        WorldFilter worldFilter,
        double backpackChance,
        int backpackTotalMin,
        int backpackTotalMax,
        int backpackKindsMin,
        int backpackKindsMax,
        List<PlacementEntry> entries,
        double maxDistance,
        int maxPerChase,
        long cooldownMillis,
        int buildDelayTicks,
        double verticalDrop,
        int blockLifetimeSeconds,
        boolean stairUp,
        double stairMinDistance,
        boolean pillarUp,
        int pillarUpMaxHeight,
        int pillarFarHeight,
        double pillarNearDistance,
        boolean bridge,
        int bridgeMaxLength,
        int laneSearchRadius,
        boolean requireAdjacent,
        double maxFacingAngle,
        boolean verticalAggro,
        boolean specialOnlyUnderTarget,
        boolean spreadCrowd,
        double spacingRadius,
        boolean smartAscend,
        long retryCooldownMillis,
        boolean pickupEnabled,
        double pickupRadius,
        double pickupReach,
        boolean pickupBlocks,
        boolean pickupPickaxes,
        boolean pickupOnlyEquipped) {

    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    public PlacementEntry entryById(String id) {
        for (PlacementEntry entry : entries) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }
}
