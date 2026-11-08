package studio.liuye.mobinteract.placement;

import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.safety.BlockSafety;
import studio.liuye.mobinteract.util.BlockPos;
import studio.liuye.mobinteract.util.RegionAccess;

/**
 * 把“要不要放、放哪里”接到 Bukkit 世界上。
 *
 * <p>真正的决策在纯逻辑的 {@link BuildPlanner} 里（横向远 → 搭楼梯；高度过大或横向过近 →
 * 垫高；其余 → 垫高到目标高度后横向搭桥），本类负责三件事：</p>
 * <ol>
 *   <li>把怪物的位置、配置上限、上一次选定的垫高列整理成 {@link BuildPlanner.Query}；</li>
 *   <li>用 {@link BukkitBuildWorld} 提供只读世界视图；</li>
 *   <li>对选出来的落点做最后的安全校验（可及范围、{@link BlockSafety}、保护区）。</li>
 * </ol>
 *
 * <p>本类只读方块与实体，不做修改；调用方必须处于怪物所属区域的线程。返回结果里的
 * {@link Plan#lane()} 需要由调用方记到 {@code MobProfile} 上，否则下次会重新挑列。</p>
 */
public final class PlacementPlanner {

    /**
     * 一次规划的产物。
     *
     * @param target 下一步动作；{@code null} 表示这一轮不放置
     * @param lane   需要记住的垫高列；{@code null} 表示清空
     * @param reason 人话说明，供诊断与测试
     */
    public record Plan(PlacementTarget target, BuildLane lane, String reason) {

        public static Plan none(String reason, BuildLane lane) {
            return new Plan(null, lane, reason);
        }
    }

    private PlacementPlanner() {
    }

    /**
     * 规划普通方块（搭楼梯 / 垫高 / 搭桥）的下一步。
     *
     * @param mob                  发起判定的怪物
     * @param settings             放置配置快照
     * @param protection           保护区判定（接收目标位置方块，返回是否允许放置）
     * @param lane                 上次选定的垫高列，可为 {@code null}
     * @param rejectedLanes        本次追击内已经放弃的垫高列，可为 {@code null}
     * @param remainingPillarSteps 本次追击还能垫高多少格（&lt; 0 表示不限）
     * @param canDigUp             头顶那块挡路方块能不能挖掉（有镐子且可挖）
     * @return 规划结果；不需要 / 不能放置时 {@code target} 为 {@code null}
     */
    public static Plan find(Mob mob, PlacementSettings settings, Predicate<Block> protection,
            BuildLane lane, Set<BuildLane> rejectedLanes, int remainingPillarSteps, boolean canDigUp) {
        if (mob == null || !mob.isValid() || mob.isDead() || settings == null || !settings.hasEntries()) {
            return Plan.none("没有可放置的条目", lane);
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isValid() || target.isDead()) {
            return Plan.none("没有追击目标", lane);
        }
        Location mobLocation = mob.getLocation();
        Location targetLocation = target.getLocation();
        World world = mobLocation.getWorld();
        if (world == null || !world.equals(targetLocation.getWorld())) {
            return Plan.none("目标不在同一世界", lane);
        }

        BuildWorld view = new BukkitBuildWorld(world, mob);
        BuildPlanner.Query query = new BuildPlanner.Query(
                gridOf(mobLocation),
                gridOf(targetLocation),
                mob.isOnGround(),
                settings.stairUp(),
                settings.stairMinDistance(),
                settings.pillarUp(),
                settings.pillarFarHeight(),
                settings.pillarNearDistance(),
                remainingPillarSteps,
                settings.laneSearchRadius(),
                settings.bridge(),
                settings.bridgeMaxLength(),
                settings.verticalDrop(),
                lane,
                rejectedLanes,
                canDigUp);
        BuildPlanner.Plan plan = BuildPlanner.plan(query, view);
        BuildPlanner.Step step = plan.step();
        BuildLane nextLane = plan.lane();
        if (!step.isAction()) {
            return Plan.none(step.reason(), nextLane);
        }

        BlockPos pos = new BlockPos(world, step.pos().x(), step.pos().y(), step.pos().z());
        if (step.kind() == BuildPlanner.Kind.RELOCATE) {
            // 只是“走到垫高列”，具体落点下一轮再算。
            return new Plan(PlacementTarget.relocate(pos), nextLane, step.reason());
        }
        if (step.kind() == BuildPlanner.Kind.WALK) {
            // 路已经通了：让怪物自己走上去（不放过方块，也不消耗条目）。
            return new Plan(PlacementTarget.walk(pos), nextLane, step.reason());
        }
        if (!withinReach(mobLocation, pos, settings)) {
            return Plan.none("放置位置超出可及范围", nextLane);
        }
        Block block = RegionAccess.block(pos);
        if (block == null) {
            // 区块没加载或不在本区域线程：这一轮直接放弃，绝不去读别人的方块（会刷非法区域访问的堆栈）。
            return Plan.none("放置位置不属于当前区域", nextLane);
        }
        if (!BlockSafety.canPlaceAt(block, settings)) {
            return Plan.none("放置位置不能放方块", nextLane);
        }
        if (protection != null && !protection.test(block)) {
            return Plan.none("放置位置受保护", nextLane);
        }
        PlacementTarget placement = switch (step.kind()) {
            case STAIR_UP -> PlacementTarget.stairUp(pos);
            case PILLAR_UP -> PlacementTarget.pillarUp(pos);
            default -> PlacementTarget.bridge(pos);
        };
        return new Plan(placement, nextLane, step.reason());
    }

    /**
     * “只往目标脚下放”专用：TNT / 水桶 / 熔岩桶的功能性落点。
     *
     * <p>落在目标（通常是玩家）所在的那一格，并沿用同一套限制：必须紧贴怪物、
     * 位置可放置、未受保护。因为目标就站在这一格里，这里不做“是否被生物占位”的检查。</p>
     *
     * @return 目标脚下位置；条件不满足时返回 {@code null}
     */
    public static PlacementTarget findUnderTarget(Mob mob, PlacementSettings settings,
            Predicate<Block> protection) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return null;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isValid()) {
            return null;
        }
        Location targetLocation = target.getLocation();
        BlockPos candidate = new BlockPos(targetLocation.getWorld(),
                targetLocation.getBlockX(), targetLocation.getBlockY(), targetLocation.getBlockZ());
        Block pos = RegionAccess.block(candidate);
        if (pos == null) {
            return null;   // 目标在别的区域 / 区块没加载：这一轮不往它脚下放
        }
        if (!withinReach(mob.getLocation(), candidate, settings)) {
            return null;
        }
        if (!BlockSafety.canPlaceAt(pos, settings)) {
            return null;
        }
        if (protection != null && !protection.test(pos)) {
            return null;
        }
        return PlacementTarget.underTarget(candidate);
    }

    /**
     * 放置位置是否够得着。
     *
     * <p>{@code require-adjacent} 默认开启：目标方块必须与怪物身体紧贴
     * （水平 ±1 格、竖直 -2..+1 格），从根本上杜绝“隔着几格凭空放方块”。
     * 这里允许 −2 是因为**往下搭一层的落点**就在“正前方、低两格”：站上去正好是一个 1 格落差。
     * 关闭时退回按 {@code max-distance} 判断。</p>
     */
    private static boolean withinReach(Location mobLocation, BlockPos pos, PlacementSettings settings) {
        if (!settings.requireAdjacent()) {
            return pos.distanceTo(mobLocation) <= settings.maxDistance();
        }
        Block base = mobLocation.getBlock();
        int dx = Math.abs(pos.x() - base.getX());
        int dz = Math.abs(pos.z() - base.getZ());
        int dy = pos.y() - base.getY();
        return dx <= 1 && dz <= 1 && dy >= -2 && dy <= 1;
    }

    private static GridPos gridOf(Location location) {
        return new GridPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
