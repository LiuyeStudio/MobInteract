package studio.liuye.mobinteract.mining;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.safety.BlockSafety;
import studio.liuye.mobinteract.util.BlockPos;

/**
 * 决定“要不要挖、挖哪一个方块”。
 *
 * <p>两种触发方式：</p>
 * <ol>
 *   <li><b>寻路被阻挡</b>：沿“眼睛 → 追击目标”的射线找第一处不可通过的方块，
 *       只要它是可挖的方块就作为目标；第一处不可挖时直接放弃（不会隔着基岩去挖后面的方块）。</li>
 *   <li><b>指定方块</b>：面前一定距离内出现 {@code trigger.configured-blocks.blocks}
 *       里的方块时触发，不要求挡住视线。</li>
 * </ol>
 *
 * <p>本类只做判定与换算，不修改任何方块；调用方必须处于怪物所属区域的线程。</p>
 */
public final class MiningTargetFinder {

    private static final double SAMPLE_STEP = 0.25D;
    private static final int MAX_SAMPLES = 64;

    /** 挖“头顶方块”时，目标至少要高出这么多格（否则算平地追击，别乱挖）。 */
    private static final double CEILING_MIN_RISE = 1.5D;

    /** 挖“脚下”——目标水平方向要在这个距离内，远了应该走 / 搭，而不是原地挖竖井。 */
    private static final double DIG_BELOW_RADIUS = 3.0D;

    /** 已经提示过“超过 hard-cap 所以挖不动”的材质，避免刷屏。 */
    private static final Set<Material> CAP_REPORTED = ConcurrentHashMap.newKeySet();

    private MiningTargetFinder() {
    }

    /**
     * @param mob        发起判定的怪物
     * @param settings   挖掘配置快照
     * @param protection 保护区判定（接收目标方块，返回是否允许修改）
     * @return 目标方块；无可挖目标时返回 {@code null}
     */
    public static MiningCandidate find(Mob mob, MiningSettings settings, Predicate<Block> protection) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return null;
        }
        // 工具过滤：要求持镐时，没有镐子的怪物直接不参与挖掘；
        // 否则按当前手持镐子的材质换算速度（空手用 hand-speed）。
        double toolSpeed = MiningTool.speedFactor(mob, settings);
        if (toolSpeed <= 0.0D) {
            return null;
        }
        LivingEntity target = mob.getTarget();
        if (settings.debug()) {
            Bukkit.getLogger().info("[MobInteract][debug] find: tool=" + String.format(java.util.Locale.ROOT, "%.2f", toolSpeed)
                    + " target=" + (target == null ? "none" : target.getType() + " @ " + target.getLocation().toVector())
                    + " mob=" + mob.getLocation().toVector()
                    + " ground=" + mob.isOnGround());
        }
        if (target != null && target.isValid()) {
            // 优先“挖掉头顶那一格”：目标在上方时，挡住自己往上垫的往往就是头顶这块方块，
            // 挖掉它比绕到旁边另起一列更直接。
            if (settings.triggerCeilingBlocked()) {
                MiningCandidate ceiling = scanCeiling(mob, target, settings, protection, toolSpeed);
                if (ceiling != null) {
                    return ceiling;
                }
            }
            // 其次“挖掉自己脚下那一格”：目标在下面、又走不过去时，挖穿脚下的地面掉下去。
            if (settings.triggerDigBelowSelf()) {
                MiningCandidate belowSelf = scanBelowSelf(mob, target, settings, protection, toolSpeed);
                if (belowSelf != null) {
                    return belowSelf;
                }
            }
            // 再其次“挖目标脚下的方块”，让站在高处 / 掩体后的目标掉下来。
            if (settings.digUnderTarget()) {
                MiningCandidate under = scanUnderTarget(mob, target, settings, protection, toolSpeed);
                if (under != null) {
                    return under;
                }
            }
            if (settings.triggerPathBlocked()) {
                MiningCandidate candidate = scanTowardTarget(mob, target, settings, protection, toolSpeed);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        if (settings.triggerConfiguredBlocks() && !settings.configuredBlocks().isEmpty()) {
            return scanConfiguredBlocks(mob, settings, protection, toolSpeed);
        }
        return null;
    }

    /**
     * 目标在下面时，能不能挖掉<b>自己脚下那一格</b>，掉下去继续靠近。
     *
     * <p>出手条件（都满足才挖）：</p>
     * <ul>
     *   <li>目标至少比自己低 1.5 格；</li>
     *   <li>目标就在附近（水平 {@value #DIG_BELOW_RADIUS} 格以内）——再远就应该走过去 / 搭下去，
     *       而不是原地挖一口竖井；</li>
     *   <li>自己站在地上（不会在掉落 / 游泳时挖）；</li>
     *   <li>脚下再下面一格不是液体（免得直接把水 / 熔岩放进坑里）；</li>
     *   <li>方块本身可挖、未受保护、且不是怪物自己刚放下的方块。</li>
     * </ul>
     */
    private static MiningCandidate scanBelowSelf(Mob mob, LivingEntity target, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        Location location = mob.getLocation();
        if (location.getY() - target.getY() < 1.5D) {
            return null;
        }
        if (Math.hypot(target.getX() - location.getX(), target.getZ() - location.getZ()) > DIG_BELOW_RADIUS) {
            return null;
        }
        if (!mob.isOnGround()) {
            return null;
        }
        Block below = location.getBlock().getRelative(0, -1, 0);
        if (below.getRelative(0, -1, 0).isLiquid()) {
            return null;
        }
        return evaluate(mob, below, settings, protection, toolSpeed);
    }

    /**
     * 自己头顶那一格（脚上方第 2 格）被方块压着时，能不能直接挖掉它。
     *
     * <p>只在“目标明显更高（≥1.5 格）”时出手：那种情况下怪物要往上垫，头顶方块正是拦路的东西。</p>
     */
    private static MiningCandidate scanCeiling(Mob mob, LivingEntity target, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        if (target.getY() - mob.getLocation().getY() < CEILING_MIN_RISE) {
            return null;
        }
        Block above = mob.getLocation().getBlock().getRelative(0, 2, 0);
        if (above.isPassable()) {
            return null;
        }
        return evaluate(mob, above, settings, protection, toolSpeed);
    }

    /**
     * 这只怪物现在能不能挖掉该方块（工具、材料、耗时、可破坏性、可及范围）。
     *
     * <p>给放置行为用：判断“头顶这块挡住垫高的方块，是不是值得先挖掉”。保护区判定不在这里做，
     * 真正开挖时 {@link #find} 会再校验一次。</p>
     */
    public static boolean canMine(Mob mob, MiningSettings settings, Block block) {
        if (mob == null || block == null || !mob.isValid() || mob.isDead()) {
            return false;
        }
        double toolSpeed = MiningTool.speedFactor(mob, settings);
        if (toolSpeed <= 0.0D) {
            return false;
        }
        return evaluate(mob, block, settings, null, toolSpeed) != null;
    }

    /**
     * 目标正下方的那一格（目标站立的面）。
     *
     * <p>只有“目标所在高度比怪物高”或“目标在怪物附近的高台上”时才有意义，
     * 但仍然要求该方块本身在 {@code max-distance} 内、可挖、未受保护。</p>
     */
    private static MiningCandidate scanUnderTarget(Mob mob, LivingEntity target, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        Block under = target.getLocation().getBlock().getRelative(0, -1, 0);
        if (under.getType().isAir() || under.isLiquid()) {
            return null;
        }
        // 目标站在地上（脚下方块就是地面层）时，挖穿它只会连成坑，意义不大；
        // 这里只在“目标至少比怪物高 0.5 格”时出手，避免平地追击时乱挖。
        if (target.getY() - mob.getLocation().getY() < 0.5D) {
            return null;
        }
        return evaluate(mob, under, settings, protection, toolSpeed);
    }

    private static MiningCandidate scanTowardTarget(Mob mob, LivingEntity target, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        // 先处理“正前方挡路”的方块：脚下、头顶各一格。
        // 真正挡路的是这一格，优先级高于视线上的方块，否则挖完视线方块后依然过不去。
        MiningCandidate blocking = frontObstacle(mob, target, settings, protection, toolSpeed);
        if (blocking != null) {
            return blocking;
        }

        Location eye = mob.getEyeLocation();
        Location targetPoint = target.getLocation().clone().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        double distance = eye.distance(targetPoint);
        if (distance < 0.75D) {
            return null;
        }
        Vector direction = targetPoint.toVector().subtract(eye.toVector()).normalize();
        // 跳过怪物自身身体所占的那一段，避免把脚下的方块当成障碍。
        double start = Math.max(0.85D, mob.getWidth() * 0.5D + 0.6D);
        double limit = Math.min(distance, settings.maxDistance() + 1.0D);
        Set<String> visited = new HashSet<>();
        int samples = 0;
        for (double travelled = start; travelled <= limit && samples < MAX_SAMPLES;
                travelled += SAMPLE_STEP, samples++) {
            Location sample = eye.clone().add(direction.clone().multiply(travelled));
            Block block = sample.getBlock();
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!visited.add(key)) {
                continue;
            }
            if (block.isPassable()) {
                continue;
            }
            // 视线上的第一处实体方块：要么挖它，要么放弃（不穿透）。
            return evaluate(mob, block, settings, protection, toolSpeed);
        }
        return null;
    }

    /**
     * 检查怪物朝目标方向“下一步”会撞到的方块（脚下 / 头顶各一格）。
     *
     * <p>两条水平轴都试，先试水平差更大的那条：斜着站在方块拐角时（比如黑曜石围栏的角上），
     * 只算“斜向那一格”会正好从两个方块的缝里穿过去，于是判定为“前方畅通”，
     * 结果怪物对着围栏干站着、完全不去挖。分别按单轴检查就能命中真正挡路的那一块。</p>
     *
     * @return 可挖的挡路方块；没有挡路方块或不可挖时返回 {@code null}
     */
    private static MiningCandidate frontObstacle(Mob mob, LivingEntity target, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        Location location = mob.getLocation();
        int stepX = (int) Math.signum(target.getX() - location.getX());
        int stepZ = (int) Math.signum(target.getZ() - location.getZ());
        boolean xFirst = Math.abs(target.getX() - location.getX())
                >= Math.abs(target.getZ() - location.getZ());
        int[][] steps = xFirst
                ? new int[][] {{stepX, 0}, {0, stepZ}}
                : new int[][] {{0, stepZ}, {stepX, 0}};
        for (int[] step : steps) {
            if (step[0] == 0 && step[1] == 0) {
                continue;
            }
            MiningCandidate candidate = obstacleAt(mob, location,
                    location.getBlock().getRelative(step[0], 0, step[1]), settings, protection, toolSpeed);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** 检查某一格（以及它上面那格）是否是可挖的挡路方块。 */
    private static MiningCandidate obstacleAt(Mob mob, Location location, Block footBlock,
            MiningSettings settings, Predicate<Block> protection, double toolSpeed) {
        if (!footBlock.isPassable()) {
            MiningCandidate candidate = evaluate(mob, footBlock, settings, protection, toolSpeed);
            if (candidate != null) {
                return candidate;
            }
        }
        Block headBlock = footBlock.getRelative(0, 1, 0);
        if (!headBlock.isPassable()) {
            return evaluate(mob, headBlock, settings, protection, toolSpeed);
        }
        return null;
    }

    private static MiningCandidate scanConfiguredBlocks(Mob mob, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        Location location = mob.getLocation();
        Vector facing = location.getDirection().setY(0.0D);
        if (facing.lengthSquared() < 1.0E-6D) {
            return null;
        }
        facing.normalize();
        Block feet = location.getBlock();
        int reach = (int) Math.ceil(settings.maxDistance());
        for (int step = 1; step <= reach; step++) {
            int dx = (int) Math.round(facing.getX() * step);
            int dz = (int) Math.round(facing.getZ() * step);
            for (int dy = 0; dy <= 1; dy++) {
                Block block = feet.getRelative(dx, dy, dz);
                if (!settings.configuredBlocks().contains(block.getType())) {
                    continue;
                }
                MiningCandidate candidate = evaluate(mob, block, settings, protection, toolSpeed);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 目标方块是否够得着（挖掘目标判定与“挖掘中是否仍然有效”共用这一套规则，避免两处不一致）。
     *
     * <p>{@code require-adjacent} 默认开启：只有紧挨着怪物的方块才允许挖
     * （水平 ±1 格、竖直 -1..+2 格；+2 是“自己头顶那一格”，站在方块下面挖它是合理的）。
     * 关闭后按 {@code max-distance} 判断。</p>
     */
    public static boolean withinReach(Location mobLocation, BlockPos pos, MiningSettings settings) {
        if (!settings.requireAdjacent()) {
            return pos.distanceTo(mobLocation) <= settings.maxDistance();
        }
        Block base = mobLocation.getBlock();
        int dx = Math.abs(pos.x() - base.getX());
        int dz = Math.abs(pos.z() - base.getZ());
        int dy = pos.y() - base.getY();
        return dx <= 1 && dz <= 1 && dy >= -1 && dy <= 2;
    }

    private static MiningCandidate evaluate(Mob mob, Block block, MiningSettings settings,
            Predicate<Block> protection, double toolSpeed) {
        Material material = block.getType();
        if (material.isAir() || block.isLiquid()) {
            return reject(settings, block, "空气 / 液体");
        }
        if (!settings.allowsMaterial(material)) {
            return reject(settings, block, "materials 过滤（disabled / allowed）");
        }
        if (!BlockSafety.canBreak(block, settings)) {
            return reject(settings, block, "BlockSafety.canBreak 拒绝（黑名单 / 高度 / 世界边界 / 方块实体）");
        }
        BlockPos pos = BlockPos.of(block);
        if (!withinReach(mob.getLocation(), pos, settings)) {
            return reject(settings, block, "够不着（require-adjacent / max-distance）");
        }
        if (protection != null && !protection.test(block)) {
            return reject(settings, block, "保护区 / 怪物自己放的方块");
        }
        double seconds = BreakTimeCalculator.monsterSeconds(material.getHardness(),
                settings.multiplierFor(material), settings.minSeconds(), settings.hardCapSeconds(), toolSpeed);
        if (seconds < 0.0D) {
            reportCapSkip(material, settings);
            return reject(settings, block, "超过 hard-cap-seconds");
        }
        if (settings.debug()) {
            Bukkit.getLogger().info("[MobInteract][debug] 选中 " + material + " @ "
                    + block.getX() + "," + block.getY() + "," + block.getZ()
                    + " 耗时 " + String.format(java.util.Locale.ROOT, "%.2f", seconds) + "s");
        }
        return new MiningCandidate(pos, material, seconds, BreakTimeCalculator.totalTicks(seconds));
    }

    /** 调试模式下记录“这个方块为什么没被选中”。 */
    private static MiningCandidate reject(MiningSettings settings, Block block, String reason) {
        if (settings.debug()) {
            Bukkit.getLogger().info("[MobInteract][debug] 跳过 " + block.getType() + " @ "
                    + block.getX() + "," + block.getY() + "," + block.getZ() + "：" + reason);
        }
        return null;
    }

    /**
     * 因为“超过 {@code break-time.hard-cap-seconds}”而跳过某个方块时，在日志里提示一次。
     *
     * <p>这类方块（黑曜石、远古残骸、下界合金块……）看起来就是“怪物怎么不去挖”，
     * 让原因在控制台可见，比默默跳过好。</p>
     */
    private static void reportCapSkip(Material material, MiningSettings settings) {
        if (!CAP_REPORTED.add(material)) {
            return;
        }
        double base = BreakTimeCalculator.baseSeconds(material.getHardness());
        Bukkit.getLogger().info("[MobInteract] " + material + " 的挖掘耗时超过 hard-cap-seconds="
                + settings.hardCapSeconds() + "s（木镐基准约 "
                + String.format(java.util.Locale.ROOT, "%.1f", base * settings.multiplierFor(material))
                + "s），按“挖不动”跳过；要挖它请调大 break-time.hard-cap-seconds，"
                + "或调小该方块的 materials.multiplier。");
    }
}
