package studio.liuye.mobinteract.config;

import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;

/**
 * mining.yml 解析后的不可变快照。
 *
 * <p>行为模块每次启动时取一次快照，配置重载后新启动的行为使用新值，
 * 正在进行的挖掘继续使用旧值，不会被中途打断。</p>
 *
 * @param enabled               总开关
 * @param mobFilter             生物筛选规则（白名单 / 黑名单 / 全部生物）
 * @param worldFilter           世界筛选规则（留空 = 所有世界）
 * @param maxDistance           最大挖掘距离
 * @param multiplier            默认耗时倍率；以“木镐”为基准（玩家基础时间 × 本值 ÷ 镐子速度）
 * @param minSeconds            最短耗时（秒）
 * @param hardCapSeconds        耗时上限，超过即视为不可挖
 * @param tickInterval          进度推进间隔（tick）
 * @param disabledMaterials     额外禁用的方块
 * @param multiplierOverrides   逐方块倍率覆盖
 * @param allowedMaterials      总白名单；空集合表示“所有方块”
 * @param triggerPathBlocked    是否启用“寻路被阻挡”触发
 * @param triggerConfiguredBlocks 是否启用“面前出现指定方块”触发
 * @param configuredBlocks      触发用的方块列表
 * @param progressViewers       是否向附近玩家显示破坏裂纹进度
 * @param progressRadius        裂纹进度的广播半径
 * @param particles             是否产生粒子
 * @param particle              粒子类型
 * @param particleCount         每次粒子数量
 * @param sounds                是否播放音效
 * @param hitSound              挖掘中音效
 * @param breakSound            挖断音效
 * @param soundInterval         音效间隔（tick）
 * @param cooldownMillis        挖掘冷却（毫秒）
 * @param dropItems             是否掉落物品
 * @param disallowTileEntities  是否禁止挖掘方块实体
 * @param requirePickaxe        是否要求主手必须持有镐子才能挖掘
 * @param handSpeed             空手时的速度系数（相对木镐），仅在不要求镐子时生效
 * @param pickaxeSpeeds         镐子材质 → 速度系数（木镐 = 1.0）
 * @param givePickaxe           是否让怪物生成时自带镐子
 * @param givePickaxeChance     自带镐子的概率
 * @param pickaxeWeights        自带镐子的材质权重表
 * @param pickaxeDropChance     插件发放的镐子死亡掉落概率（0 = 不掉落）
 * @param digUnderTarget        是否主动挖掉目标脚下的方块（让目标掉下来）
 * @param triggerCeilingBlocked 目标在上方、且自己头顶被方块压着时，是否挖掉头顶那一格（再继续往上搭）
 * @param triggerDigBelowSelf   目标在下方、且就在附近时，是否挖掉自己脚下那一格（掉下去继续追）
 * @param retryCooldownMillis   挖掘被中断 / 目标失效后的重试冷却（毫秒）
 * @param requireAdjacent       是否要求目标方块必须紧挨着怪物（禁止隔空挖掘）
 * @param protectMobBlocks      是否禁止挖掘“怪物刚放下的方块”（避免怪物互拆）
 */
public record MiningSettings(
        boolean enabled,
        MobFilter mobFilter,
        WorldFilter worldFilter,
        double maxDistance,
        double multiplier,
        double minSeconds,
        double hardCapSeconds,
        int tickInterval,
        Set<Material> disabledMaterials,
        Map<Material, Double> multiplierOverrides,
        Set<Material> allowedMaterials,
        boolean triggerPathBlocked,
        boolean triggerConfiguredBlocks,
        Set<Material> configuredBlocks,
        boolean progressViewers,
        double progressRadius,
        boolean particles,
        Particle particle,
        int particleCount,
        boolean sounds,
        Sound hitSound,
        Sound breakSound,
        int soundInterval,
        long cooldownMillis,
        boolean dropItems,
        boolean disallowTileEntities,
        /** 调试日志：把每个候选方块的取舍原因打到控制台。 */
        boolean debug,
        boolean requirePickaxe,
        double handSpeed,
        Map<Material, Double> pickaxeSpeeds,
        boolean givePickaxe,
        double givePickaxeChance,
        Map<Material, Integer> pickaxeWeights,
        double pickaxeDropChance,
        boolean digUnderTarget,
        boolean triggerCeilingBlocked,
        boolean triggerDigBelowSelf,
        long retryCooldownMillis,
        boolean requireAdjacent,
        boolean protectMobBlocks) {

    /** 该方块是否允许被挖掘（不看世界状态，只看配置）。 */
    public boolean allowsMaterial(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        if (!allowedMaterials.isEmpty() && !allowedMaterials.contains(material)) {
            return false;
        }
        return !disabledMaterials.contains(material);
    }

    /** 该方块的实际倍率（未覆盖时用默认倍率）。 */
    public double multiplierFor(Material material) {
        Double override = multiplierOverrides.get(material);
        return override != null ? override : multiplier;
    }

    /**
     * 当前手持镐子的速度系数（相对木镐）；{@code null} 表示空手。
     *
     * <p>未在表中登记的镐子按木镐（1.0）处理；空手按 {@code handSpeed}。</p>
     */
    public double speedFor(Material heldPickaxe) {
        if (heldPickaxe == null) {
            return handSpeed;
        }
        Double speed = pickaxeSpeeds.get(heldPickaxe);
        return speed != null && speed > 0.0D ? speed : 1.0D;
    }
}
