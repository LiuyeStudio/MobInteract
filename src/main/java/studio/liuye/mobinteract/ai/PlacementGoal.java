package studio.liuye.mobinteract.ai;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.EnumSet;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.backpack.MobBackpack;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.mining.MiningTargetFinder;
import studio.liuye.mobinteract.mining.MiningTool;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.mob.MobProfile;
import studio.liuye.mobinteract.placement.AscendScout;
import studio.liuye.mobinteract.placement.BuildLane;
import studio.liuye.mobinteract.placement.PlacementExecutor;
import studio.liuye.mobinteract.placement.PlacementPlanner;
import studio.liuye.mobinteract.placement.PlacementTarget;
import studio.liuye.mobinteract.util.BlockPos;
import studio.liuye.mobinteract.util.RegionAccess;

/**
 * 放置行为（Mob Goals 实现）。
 *
 * <p>触发：追击目标与自身之间存在沟壑 / 垂直落差 / 高处目标，且怪物有背包、有剩余次数、不在冷却中。
 * 执行：从背包按条目权重抽一条并<b>立即预留（扣除）</b>，经过一段“短搭建进度”后真正放置；
 * 方块修改在目标位置的区域线程执行，结果再回到实体线程结算（失败则退回条目并进入冷却）。</p>
 *
 * <p>搭路策略：按“横向多远、高度多高”在三种之间自动选（决策在纯逻辑的 {@code BuildPlanner} 里）：</p>
 * <ul>
 *   <li>横向距离远、高度不算夸张 → <b>搭楼梯</b>：每一级同时前进一格、抬高一级；</li>
 *   <li>高度差过大 或 横向太近 → <b>原地垫高</b>：先找一列整列没有其它生物的空列，把方块垫在脚下往上爬；</li>
 *   <li>其余情况 → 先垫高到目标高度，再<b>朝目标横向搭桥</b>。</li>
 * </ul>
 * <p>选定的垫高列记在 {@link MobProfile#buildLane()} 上，不会每几 tick 换一个地方乱垫；
 * 如果这一列不在脚下，就先走过去（{@link PlacementTarget.Kind#RELOCATE}，不消耗方块）。</p>
 *
 * <p>约束：</p>
 * <ul>
 *   <li>放置位置必须与怪物相邻（见 {@code placement.require-adjacent}），不会隔空放；</li>
 *   <li>放置前怪物必须面向放置位置（见 {@code placement.max-facing-angle}），否则先转头、再重试；</li>
 *   <li>垫高必须正好在脚下这一格，怪物挪开一格就取消本次放置（避免在地上乱放方块）；</li>
 *   <li>放置时会在副手显示对应物品，避免“手里什么都没有却在放方块”的观感。</li>
 * </ul>
 */
public final class PlacementGoal implements Goal<Mob> {

    private static final long SCAN_INTERVAL_TICKS = 5L;

    /** 这一轮没有可放的落点时，隔久一点再重扫（避免每 tick 扫一遍方块）。 */
    private static final long IDLE_SCAN_INTERVAL_TICKS = 10L;

    /** 朝向不对时最多重试几次（每次间隔 2 tick）。 */
    private static final int MAX_FACING_ATTEMPTS = 10;

    /** 被同伴挤开后最多重新规划几次放置位置。 */
    private static final int MAX_REPATH_ATTEMPTS = 4;

    /** 上升侦察（含寻路计算）的最小间隔（毫秒）。 */
    private static final long SCOUT_COOLDOWN_MILLIS = 1000L;

    /** 走到垫高列 / 走上已铺好的路的最小间隔（毫秒），避免每 tick 重复下寻路指令。 */
    private static final long ADVANCE_COOLDOWN_MILLIS = 500L;

    /**
     * 建路（楼梯 / 垫高 / 桥面）每一步之间的放置间隔（毫秒）。
     *
     * <p>建路是“一次连续动作”：每一格之间如果还等满 {@code cooldown-seconds}（默认 3 秒），
     * 怪物会在爬到一半时被原版寻路叫走、从 1x1 的柱子上掉下去，或者在桥头干站着。
     * 所以建路用更短的间隔，尽快把路修通；TNT / 桶这类功能方块仍用完整冷却。</p>
     */
    private static final long ROAD_STEP_COOLDOWN_MILLIS = 200L;

    /** 同一只怪物两次“让位”之间的最小间隔（毫秒）。 */
    private static final long CROWD_SPREAD_COOLDOWN_MILLIS = 700L;

    private final MobInteractPlugin plugin;
    private final Mob mob;
    private final GoalKey<Mob> key;

    private PlacementTarget spot;
    private PlacementEntry chosen;
    private long nextScanTick;
    private boolean inProgress;
    private boolean finished;
    private ScheduledTask buildTask;
    private int facingAttempts;
    /** 是否已经从背包预留了 {@link #chosen}（失败时用于退回）。 */
    private boolean reserved;
    /** 是否已把放置任务交给执行器（在途时不退回，等回调结算）。 */
    private boolean dispatched;
    /** 被同伴挤开 / 位置被占后重新规划放置位置的次数。 */
    private int repathAttempts;
    /** 本次激活时抽中的条目（start() 直接复用，避免重复抽取导致落点与条目不匹配）。 */
    private PlacementEntry pendingEntry;

    public PlacementGoal(MobInteractPlugin plugin, Mob mob, GoalKey<Mob> key) {
        this.plugin = plugin;
        this.mob = mob;
        this.key = key;
    }

    @Override
    public boolean shouldActivate() {
        PlacementSettings settings = plugin.settings().placement();
        if (!settings.enabled() || !settings.worldFilter().allows(mob.getWorld())
                || !settings.mobFilter().allows(mob)) {
            return false;
        }
        if (!mob.isValid() || mob.isDead()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if ((target == null || !target.isValid()) && settings.verticalAggro()) {
            // 玩家悬停 / 站在头顶平台时，原版 AI 常常不会索敌（或玩家在创造模式）。
            // 这里主动锁定上方可攻击的玩家，后续的垫高逻辑才能跑起来。
            Player above = findPlayerAbove(mob);
            if (above != null) {
                mob.setTarget(above);
                target = above;
            }
        }
        if (target == null || !target.isValid()) {
            // 目标丢失：把“单次追击”的计数（放置次数 / 垫高格数 / 垫高列）在这里清零。
            // 否则一只怪追同一个玩家时，一旦用完 max-per-chase 就整局都不能再搭，
            // 看起来就是“垫到一定高度突然不搭了”，哪怕背包里还有方块。
            plugin.registry().profile(mob).noteChaseTarget(null);
            return false;
        }

        MobProfile profile = plugin.registry().profile(mob);
        if (profile.mining()) {
            // 挖掘与放置互斥：挖掘期间要一直盯着被挖的方块，不能同时铺方块。
            spot = null;
            return false;
        }
        if (profile.placementOnCooldown(System.currentTimeMillis())) {
            spot = null;
            return false;
        }
        profile.noteChaseTarget(target.getUniqueId());
        if (profile.placementsThisChase() >= settings.maxPerChase()) {
            // 说清楚为什么不动，别让服主以为插件坏了（/mobinteract inspect 能看到这句）。
            spot = null;
            profile.setLastStatus("放置次数已用完（max-per-chase=" + settings.maxPerChase() + "）");
            return false;
        }

        MobBackpack backpack = profile.decideBackpack(settings, plugin.random());
        // 保持手持方块与库存同步（背包为空时这里会清空副手）。
        plugin.heldItems().sync(mob);
        if (backpack == null || backpack.isEmpty()) {
            // 背包空了：绝不再放置。
            spot = null;
            return false;
        }
        // 一群带方块的怪物挤在同一格时，先让位散开，避免谁也放不下、还互相把对方挤进方块里。
        if (settings.spreadCrowd() && plugin.crowdGuard().isCrowded(mob, settings.spacingRadius())) {
            plugin.crowdGuard().spread(mob, settings.spacingRadius(), CROWD_SPREAD_COOLDOWN_MILLIS);
            spot = null;
            return false;
        }

        // 节流：重扫方块（挑选垫高列 / 找落点）很贵，按 tick 间隔复用上一次的结果。
        long ticks = mob.getTicksLived();
        if (ticks < nextScanTick) {
            return spot != null && spotValid(settings);
        }
        nextScanTick = ticks + SCAN_INTERVAL_TICKS;

        // 目标在更高处时，先侦察“能不能直接走过去 / 沿附近台阶绕上去”，
        // 只有确实上不去才动手搭方块，而不是一开始就呆在玩家正下方垫高。
        if (settings.smartAscend() && target.getLocation().getY() - mob.getLocation().getY() >= 1.0D
                && !profile.busy()) {
            long now = System.currentTimeMillis();
            if (now - profile.lastScoutAt() >= SCOUT_COOLDOWN_MILLIS) {
                profile.setLastScoutAt(now);
                AscendScout.Decision decision = AscendScout.decide(mob, target);
                if (decision != AscendScout.Decision.BUILD) {
                    profile.setLastStatus(decision == AscendScout.Decision.PATH_EXISTS
                            ? "寻路可达：直接追过去" : "沿附近台阶绕上去");
                    spot = null;
                    return false;
                }
            }
        }
        // 先抽这次要用哪一条目，再按条目类型决定落点：
        // TNT / 水桶 / 熔岩桶默认只往目标脚下放，不参与搭桥 / 垫高。
        PlacementEntry planned = backpack.pick(plugin.random());
        if (planned == null) {
            spot = null;
            return false;
        }
        pendingEntry = planned;
        if (planned.isSpecial() && settings.specialOnlyUnderTarget()) {
            spot = PlacementPlanner.findUnderTarget(mob, settings, this::canChange);
            return spot != null;
        }

        PlacementPlanner.Plan plan = plan(settings, profile);
        // 垫高列必须记下来，否则下一次扫描会换一列，结果就是在附近乱垫方块。
        profile.setBuildLane(plan.lane());
        PlacementTarget plannedSpot = plan.target();
        if (plannedSpot != null && plannedSpot.isMoveOnly()) {
            // 只移动不放置：走到选定的垫高列，或走上已经铺好的路（不消耗方块）。
            advance(profile, plannedSpot);
            spot = null;
            return false;
        }
        spot = plannedSpot;
        if (spot == null) {
            profile.setLastStatus(plan.reason());
            nextScanTick = ticks + IDLE_SCAN_INTERVAL_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        if (finished || !mob.isValid() || mob.isDead()) {
            return false;
        }
        if (plugin.registry().profile(mob).mining()) {
            // 挖掘优先级更高（goal 序号也更小）：挖掘期间立刻让出，避免边挖边放。
            return false;
        }
        if (inProgress) {
            return true;
        }
        return spotValid(plugin.settings().placement());
    }

    @Override
    public void start() {
        PlacementSettings settings = plugin.settings().placement();
        MobProfile profile = plugin.registry().profile(mob);
        MobBackpack backpack = profile.backpack();
        if (backpack == null || backpack.isEmpty() || spot == null || spot.isMoveOnly()) {
            finished = true;
            return;
        }
        PlacementEntry entry = pendingEntry != null && backpack.amountOf(pendingEntry) > 0
                ? pendingEntry
                : backpack.pick(plugin.random());
        pendingEntry = null;
        if (entry == null || !backpack.take(entry)) {
            // 库存不足（理论上 isEmpty 已挡住，这里再兜一层）：不放置。
            finished = true;
            return;
        }
        chosen = entry;
        reserved = true;
        inProgress = true;
        finished = false;
        facingAttempts = 0;
        repathAttempts = 0;
        Pathfinder pathfinder = mob.getPathfinder();
        if (pathfinder != null) {
            pathfinder.stopPathfinding();
        }
        // 垫高是把方块放在“自己脚下”，不需要低头去“面对”它；
        // 强行 lookAt 脚下会让怪物一边爬一边点头，而且一旦原版 AI 抢回视线，
        // 面向判定就会反复失败 → 退条目 → 重试，表现为“爬到一定高度就卡住不搭”。
        if (!spot.isPillarUp()) {
            mob.lookAt(spot.pos().center());
        }
        profile.setHeldEntry(entry);
        plugin.heldItems().sync(mob);
        profile.setBusy(true);
        profile.setLastStatus("放置准备 " + entry.id() + " " + spot.kind());
        buildTask = plugin.schedulers().entityDelayed(mob, Math.max(0, settings.buildDelayTicks()),
                this::deliver);
    }

    @Override
    public void tick() {
        if (finished || spot == null) {
            return;
        }
        long ticks = mob.getTicksLived();
        if (ticks % 10L == 0L) {
            Pathfinder pathfinder = mob.getPathfinder();
            if (pathfinder != null) {
                pathfinder.stopPathfinding();
            }
        }
        // 持续朝向放置位置，保证“面对才放”（垫高除外，见 start()）。
        if (!spot.isPillarUp()) {
            mob.lookAt(spot.pos().center());
        }
    }

    @Override
    public void stop() {
        ScheduledTask task = buildTask;
        if (task != null) {
            task.cancel();
            buildTask = null;
        }
        // 预留但没走完流程的条目在这里退回；已经交给执行器的由回调结算，避免重复退还。
        if (reserved && !dispatched) {
            refund();
        }
        dispatched = false;
        plugin.heldItems().sync(mob);
        spot = null;
        chosen = null;
        pendingEntry = null;
        plugin.registry().profile(mob).setBusy(false);
        inProgress = false;
        finished = false;
        facingAttempts = 0;
        repathAttempts = 0;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 在上方寻找一个可攻击的玩家（生存 / 冒险模式，且能看见）。
     *
     * <p>仅用于“目标为空”时纠正原版不索敌的情况；创造 / 旁观玩家不会被锁定。</p>
     */
    private Player findPlayerAbove(Mob mob) {
        Location base = mob.getLocation();
        Player best = null;
        double bestHeight = Double.MAX_VALUE;
        for (Entity entity : mob.getNearbyEntities(3.0D, 16.0D, 3.0D)) {
            if (!(entity instanceof Player player) || !player.isValid() || player.isDead()) {
                continue;
            }
            GameMode mode = player.getGameMode();
            if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
                continue;
            }
            double above = player.getLocation().getY() - base.getY();
            if (above < 1.5D || above > 16.0D) {
                continue;
            }
            if (!mob.hasLineOfSight(player)) {
                continue;
            }
            if (above < bestHeight) {
                bestHeight = above;
                best = player;
            }
        }
        return best;
    }

    /** 保护区判定（供规划与执行期共用）。 */
    private boolean canChange(Block block) {
        return plugin.protection().canChange(block, mob);
    }

    /** 交给 {@link PlacementPlanner} 规划下一步，并把单次追击的垫高预算换算进去。 */
    private PlacementPlanner.Plan plan(PlacementSettings settings, MobProfile profile) {
        int remainingPillarSteps = settings.pillarUpMaxHeight() <= 0
                ? -1
                : Math.max(0, settings.pillarUpMaxHeight() - profile.pillarSteps());
        return PlacementPlanner.find(mob, settings, this::canChange, profile.buildLane(),
                profile.rejectedLanes(), remainingPillarSteps, canDigCeiling());
    }

    /**
     * 头顶那一格挡路时能不能“先挖掉再继续垫”。
     *
     * <p>要求：挖掘功能开启且这只怪被挖掘规则接管、<b>主手确实有镐子</b>、
     * 头顶那块方块本身可挖且够得着。条件不满足时放置行为还是按老办法换一列。</p>
     */
    private boolean canDigCeiling() {
        MiningSettings mining = plugin.settings().mining();
        if (!mining.enabled() || !mining.triggerCeilingBlocked()
                || !mining.worldFilter().allows(mob.getWorld())
                || !mining.mobFilter().allows(mob)) {
            return false;
        }
        if (MiningTool.heldPickaxe(mob) == null) {
            // 用户要求：只有“有镐子”的怪物才走“先挖头顶”这条路。
            return false;
        }
        Block above = mob.getLocation().getBlock().getRelative(0, 2, 0);
        return MiningTargetFinder.canMine(mob, mining, above);
    }

    /**
     * 只移动、不放置：给原版寻路下一个“往前走”的指令（节流）。
     *
     * <p>两种来源都走这里：垫高列不在脚下（{@code RELOCATE}），以及路已经通了、
     * 只需要走上刚铺好的那一格（{@code WALK}）。这一段不消耗背包条目，
     * {@link #start()} 根本不会触发。</p>
     */
    private void advance(MobProfile profile, PlacementTarget destination) {
        long now = System.currentTimeMillis();
        if (now - profile.lastAdvanceAt() < ADVANCE_COOLDOWN_MILLIS) {
            return;
        }
        profile.setLastAdvanceAt(now);
        Pathfinder pathfinder = mob.getPathfinder();
        boolean moved = pathfinder != null && pathfinder.moveTo(destination.pos().center(), 1.0D);
        if (moved) {
            profile.setLastStatus(destination.isRelocate() ? "走到空列准备垫高" : "沿已铺好的路前进");
            return;
        }
        if (destination.isRelocate()) {
            // 走不过去（被挡 / 隔着沟）：记住这一列，本次追击不再挑它。
            profile.rejectLane(new BuildLane(destination.pos().x(), destination.pos().z()));
            profile.setLastStatus("垫高列走不过去，换一列");
        }
    }

    /**
     * 方块放好之后，明确让怪物走上刚铺的那一格。
     *
     * <p>不这么做的话，原版寻路会因为“目标还在沟壑对面、根本到不了”而不给移动指令，
     * 怪物铺一格就干站着，看起来就是“搭桥时莫名其妙停下”。垫高由执行器负责把怪物抬上去，
     * 不需要这一步。</p>
     */
    private void stepOnto(PlacementTarget placed) {
        if (placed.isMoveOnly() || placed.isPillarUp()) {
            return;
        }
        Pathfinder pathfinder = mob.getPathfinder();
        if (pathfinder == null) {
            return;
        }
        pathfinder.moveTo(placed.pos().offset(0, 1, 0).center(), 1.0D);
    }

    private boolean spotValid(PlacementSettings settings) {
        if (spot == null || spot.isMoveOnly()) {
            return false;
        }
        if (spot.isPillarUp()) {
            // 垫高必须正好在脚下这一格：挪开一格就会变成在旁边乱放方块。
            return spot.pos().equals(BlockPos.of(mob.getLocation().getBlock()))
                    && RegionAccess.isReplaceable(spot.pos());
        }
        // 楼梯 / 桥面必须仍在“身前相邻一格”：怪物被挤开以后照旧放，就会多出一块乱放的方块。
        if (!isSingleStepForward(mob.getLocation())) {
            return false;
        }
        if (settings.requireAdjacent()) {
            Location base = mob.getLocation();
            int dx = Math.abs(spot.pos().x() - base.getBlockX());
            int dz = Math.abs(spot.pos().z() - base.getBlockZ());
            int dy = spot.pos().y() - base.getBlockY();
            // −2 是“往下搭一层”的落点（正前方、低两格），仍然紧贴怪物。
            if (dx > 1 || dz > 1 || dy < -2 || dy > 1) {
                return false;
            }
        } else if (spot.pos().distanceTo(mob.getLocation()) > settings.maxDistance()) {
            return false;
        }
        return RegionAccess.isReplaceable(spot.pos());
    }

    /** 落点是否正好是身前那一格（同一高度、下方一格或下潜时再下一格）。 */
    private boolean isSingleStepForward(Location base) {
        int dx = Math.abs(spot.pos().x() - base.getBlockX());
        int dz = Math.abs(spot.pos().z() - base.getBlockZ());
        int dy = spot.pos().y() - base.getBlockY();
        return dx + dz == 1 && dy >= -2 && dy <= 0;
    }

    /** “短搭建进度”结束后真正放置（实体线程）。 */
    private void deliver() {
        buildTask = null;
        PlacementEntry entry = chosen;
        PlacementTarget target = spot;
        if (entry == null || target == null || finished || !reserved || !mob.isValid()) {
            finished = true;
            return;
        }
        PlacementSettings settings = plugin.settings().placement();
        MobProfile profile = plugin.registry().profile(mob);

        // 一群怪物挤在一起时，规划好的位置可能已经被同伴占住，或者自己被挤出了 1 格范围。
        // 这里重新校验并重新规划，避免所有人都在“失败—冷却”之间打转、谁也放不了。
        if (!spotValid(settings)) {
            repathAttempts++;
            PlacementPlanner.Plan replanned = repathAttempts <= MAX_REPATH_ATTEMPTS
                    ? plan(settings, profile)
                    : null;
            if (replanned != null) {
                profile.setBuildLane(replanned.lane());
            }
            if (replanned != null && replanned.target() != null && !replanned.target().isMoveOnly()) {
                spot = replanned.target();
                profile.setLastStatus("重新寻找放置位置…");
                buildTask = plugin.schedulers().entityDelayed(mob, 2L, this::deliver);
                return;
            }
            profile.setLastStatus("放置取消：位置被占用");
            refund();
            plugin.heldItems().sync(mob);
            finished = true;
            return;
        }

        // 必须面向放置位置：转过去再等两 tick 重试，超过上限就放弃并退回条目。
        // 垫高（方块在脚下）不做这个检查：低头看脚下既没意义，又会在原版 AI 抢回视线时反复失败，
        // 表现就是“爬到一定高度后一直点头、不再往上搭”。
        if (!target.isPillarUp() && !isFacing(target, settings.maxFacingAngle())) {
            facingAttempts++;
            mob.lookAt(target.pos().center());
            if (facingAttempts <= MAX_FACING_ATTEMPTS) {
                profile.setLastStatus("转向放置位置…");
                buildTask = plugin.schedulers().entityDelayed(mob, 2L, this::deliver);
                return;
            }
            profile.setLastStatus("放置取消：无法面向放置位置");
            refund();
            plugin.heldItems().sync(mob);
            finished = true;
            return;
        }

        dispatched = true;
        plugin.placementExecutor().execute(mob, entry, target, settings, success -> {
            dispatched = false;
            finished = true;
            inProgress = false;
            if (!success) {
                // 放置被拒绝（保护区、位置被占、区块未加载等）：退回条目 + 短暂冷却。
                // 垫高失败时记住这一列放不下，免得对着同一个地方反复重试。
                if (target.isPillarUp()) {
                    profile.rejectLane(new BuildLane(target.pos().x(), target.pos().z()));
                } else {
                    profile.setBuildLane(null);
                }
                refund();
                profile.setPlacementCooldownUntil(System.currentTimeMillis() + settings.retryCooldownMillis());
                profile.setLastStatus("放置失败 " + entry.id() + " @ "
                        + target.pos().x() + "," + target.pos().y() + "," + target.pos().z());
                plugin.heldItems().sync(mob);
                return;
            }
            // 成功：条目在 take() 时已经扣除，这里标记结算完成，避免 stop() 再退回。
            reserved = false;
            if (!PlacementExecutor.consumesOnSuccess(entry)) {
                // 配置显式关闭了桶的消耗（consume-bucket: false）才把桶“回收”；
                // 默认 true：水/熔岩桶放一次就连桶一起消失。
                MobBackpack backpack = profile.backpack();
                if (backpack != null) {
                    backpack.giveBack(entry);
                }
            }
            profile.incrementPlacementsThisChase();
            if (target.isPillarUp()) {
                profile.incrementPillarSteps();
            }
            // 建路（楼梯 / 垫高 / 搭桥）是连续动作：每格之间只等很短时间，否则会被原版寻路叫下柱子、
            // 或者在桥头干站着。功能方块（TNT / 桶）仍按 cooldown-seconds 冷却。
            long cooldown = isRoadKind(target)
                    ? Math.min(settings.cooldownMillis(), ROAD_STEP_COOLDOWN_MILLIS)
                    : settings.cooldownMillis();
            profile.setPlacementCooldownUntil(System.currentTimeMillis() + cooldown);
            // 关键：铺好一格之后明确让怪物走上去。原版寻路常常因为“目标还在沟壑对面”而不动。
            stepOnto(target);
            profile.setLastStatus("已放置 " + entry.id() + " " + target.kind());
            plugin.heldItems().sync(mob);
        });
    }

    /** 该落点是否属于“建路”（楼梯 / 垫高 / 桥面），这类放置是连续动作。 */
    private static boolean isRoadKind(PlacementTarget target) {
        return target.isStairUp() || target.isPillarUp() || target.kind() == PlacementTarget.Kind.BRIDGE;
    }

    /** 怪物视线方向与“眼睛 → 放置位置中心”的夹角是否在允许范围内。 */
    private boolean isFacing(PlacementTarget target, double maxAngleDegrees) {
        Location eye = mob.getEyeLocation();
        Vector toTarget = target.pos().center().toVector().subtract(eye.toVector());
        if (toTarget.lengthSquared() < 1.0E-4D) {
            return true;
        }
        double degrees = Math.toDegrees(eye.getDirection().angle(toTarget));
        return degrees <= maxAngleDegrees;
    }

    /** 退回预留的条目。 */
    private void refund() {
        if (!reserved) {
            return;
        }
        reserved = false;
        MobBackpack backpack = plugin.registry().profile(mob).backpack();
        if (backpack != null) {
            backpack.giveBack(chosen);
        }
    }
}
