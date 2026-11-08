package studio.liuye.mobinteract.placement;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.Pathfinder;

import java.lang.reflect.Method;

/**
 * 上升 / 跨越的“先侦察再动手”策略。
 *
 * <p>目标位置上移时，先按顺序判断：</p>
 * <ol>
 *   <li>原版寻路本来就能走到目标附近 → 直接走，不搭方块；</li>
 *   <li>附近存在可以踏上去的台阶 / 坡道（比当前位置高 1~3 格、能站人、离目标更近）→ 走过去；</li>
 *   <li>都没有 → 才交给放置行为去搭台阶 / 垫高 / 架桥。</li>
 * </ol>
 *
 * <p>这样怪物不会一看到玩家在头顶就呆在正下方垂直垫高，而是优先利用地形绕上去。
 * 侦察含寻路计算，调用方需要自行节流（见 {@code PlacementGoal}）。</p>
 */
public final class AscendScout {

    private static final int SCAN_RADIUS = 3;
    private static final int MAX_STEP_UP = 3;
    /** 目标位置寻路时允许的最大距离（格）。 */
    private static final int PATH_RANGE = 24;

    /**
     * 带距离上限的 {@code findPath(Location, int)} 只存在于较新版本的服务端
     * （Paper/Folia 1.21.8 上只有无参距离版本），所以这里用反射做一次能力探测，
     * 保证同一个 jar 新旧服务端都能用。
     */
    private static final Method RANGED_FIND_PATH = lookupRangedFindPath();

    private static Method lookupRangedFindPath() {
        try {
            return Pathfinder.class.getMethod("findPath", Location.class, int.class);
        } catch (NoSuchMethodException | LinkageError ignored) {
            return null;
        }
    }

    private AscendScout() {
    }

    /** 按能力选择带距离上限的重载，缺失时退回默认距离版本。 */
    private static Pathfinder.PathResult findPath(Pathfinder pathfinder, Location location, int range) {
        if (RANGED_FIND_PATH != null) {
            try {
                return (Pathfinder.PathResult) RANGED_FIND_PATH.invoke(pathfinder, location, range);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 落到下面默认距离的调用
            }
        }
        return pathfinder.findPath(location);
    }

    /** 侦察结论。 */
    public enum Decision {
        /** 原版寻路可达，已经让它走过去。 */
        PATH_EXISTS,
        /** 发现了更高的落脚点，已经让它走过去。 */
        MOVING_TO_STEP,
        /** 没有现成的路，需要搭方块。 */
        BUILD
    }

    /**
     * 侦察一次。
     *
     * @param mob    被接管的怪物
     * @param target 追击目标
     * @return 侦察结论
     */
    public static Decision decide(Mob mob, LivingEntity target) {
        if (mob == null || !mob.isValid() || mob.isDead()
                || target == null || !target.isValid()) {
            return Decision.BUILD;
        }
        Pathfinder pathfinder = mob.getPathfinder();
        if (pathfinder == null) {
            return Decision.BUILD;
        }

        // 1) 原版能不能直接走到目标脚下 / 身边？
        Pathfinder.PathResult path = findPath(pathfinder, target.getLocation(), PATH_RANGE);
        if (path != null && path.canReachFinalPoint()) {
            pathfinder.moveTo(path, 1.0D);
            return Decision.PATH_EXISTS;
        }

        // 2) 附近有没有可以踏上去的台阶 / 坡道？
        Location step = findStep(mob, target);
        if (step != null && pathfinder.moveTo(step, 1.0D)) {
            return Decision.MOVING_TO_STEP;
        }
        return Decision.BUILD;
    }

    /**
     * 在怪物周围找“比现在高、能站人、且离目标更近”的落脚点。
     *
     * <p>优先选高度增量小、水平更接近目标的点：这样是沿地形往上爬，
     * 而不是原地垒方块。</p>
     */
    private static Location findStep(Mob mob, LivingEntity target) {
        Location base = mob.getLocation();
        int baseY = base.getBlockY();
        int targetY = target.getLocation().getBlockY();
        if (targetY <= baseY) {
            return null;
        }
        int maxUp = Math.min(MAX_STEP_UP, targetY - baseY);
        double currentHorizontal = horizontalDistance(base, target.getLocation());

        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 1; dy <= maxUp; dy++) {
                    Location candidate = new Location(base.getWorld(),
                            base.getBlockX() + dx + 0.5D,
                            baseY + dy,
                            base.getBlockZ() + dz + 0.5D);
                    double horizontal = horizontalDistance(candidate, target.getLocation());
                    if (horizontal + 0.5D >= currentHorizontal) {
                        // 必须比现在更接近目标，否则绕上去也没意义。
                        continue;
                    }
                    if (!walkable(candidate)) {
                        continue;
                    }
                    double score = dy * 100.0D + horizontal;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** 该位置能否站立：脚下有支撑，脚与头两格通透。 */
    private static boolean walkable(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        if (location.getY() < world.getMinHeight() + 1 || location.getY() >= world.getMaxHeight() - 2) {
            return false;
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        try {
            Block feet = location.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            Block below = feet.getRelative(0, -1, 0);
            return feet.isPassable() && head.isPassable() && !below.isPassable();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
