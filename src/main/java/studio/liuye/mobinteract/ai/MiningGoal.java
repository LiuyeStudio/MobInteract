package studio.liuye.mobinteract.ai;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.EnumSet;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.api.MobBlockChangeEvent;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.mining.MiningCandidate;
import studio.liuye.mobinteract.mining.MiningTargetFinder;
import studio.liuye.mobinteract.mob.MobProfile;
import studio.liuye.mobinteract.safety.BlockSafety;
import studio.liuye.mobinteract.util.BlockPos;

/**
 * 挖掘行为（Mob Goals 实现）。
 *
 * <p>优先级高于原版移动目标，抢到 MOVE 标记后原版追击 / 寻路自然让位；
 * 不会删除任何原版行为。挖掘期间还会周期性调用 {@code stopPathfinding()}
 * 抑制残留路径，避免自定义行为与原版 AI 打架。</p>
 *
 * <p>所有回调都在怪物所属区域的线程上执行；方块移除则投递到目标方块所在区域，
 * 完成后再切回实体调度器收尾。</p>
 */
public final class MiningGoal implements Goal<Mob> {

    /** 目标方块的重新扫描间隔（tick）。 */
    private static final long SCAN_INTERVAL_TICKS = 5L;

    /** 同一方块被中断后，多少 tick 内重新锁定仍可续挖（100 tick = 5 秒）。 */
    private static final long RESUME_WINDOW_TICKS = 100L;

    /** 挖掘任务连续这么多 tick 没有推进就判定停滞（3 秒）。 */
    private static final long STALL_TIMEOUT_TICKS = 60L;

    /** 目标位置无法调度时的最大重试次数（每次间隔 1 tick）。 */
    private static final int MAX_BREAK_ATTEMPTS = 40;

    private final MobInteractPlugin plugin;
    private final Mob mob;
    private final GoalKey<Mob> key;

    private MiningCandidate candidate;
    private long nextScanTick;
    private int elapsedTicks;
    private float lastProgress = -1.0F;
    private volatile ScheduledTask progressTask;
    private boolean breaking;
    private boolean finished;

    /**
     * 当前挖掘会话的目标（位置 + 材质）与“被中断的时刻”。
     *
     * <p>用于解决“进度被重置”：目标因故被重新激活时（玩家点击方块、原版 AI 让位、
     * 配置热重载等），只要还是同一个方块且在恢复窗口内，就接着原进度继续挖，
     * 而不是从 0 重新开始。</p>
     */
    private BlockPos pendingFor;
    private long pendingSince;
    private long lastProgressTick;

    private BlockPos sessionPos;
    private Material sessionMaterial;
    private long sessionPausedAt;
    private int breakAttempts;
    private long breakingSince;

    public MiningGoal(MobInteractPlugin plugin, Mob mob, GoalKey<Mob> key) {
        this.plugin = plugin;
        this.mob = mob;
        this.key = key;
    }

    @Override
    public boolean shouldActivate() {
        MiningSettings settings = plugin.settings().mining();
        if (!settings.enabled() || !settings.worldFilter().allows(mob.getWorld())
                || !settings.mobFilter().allows(mob)) {
            return false;
        }
        if (!mob.isValid() || mob.isDead()) {
            return false;
        }
        MobProfile profile = plugin.registry().profile(mob);
        if (profile.miningOnCooldown(System.currentTimeMillis())) {
            candidate = null;
            return false;
        }

        long ticks = mob.getTicksLived();
        if (candidate != null && ticks < nextScanTick) {
            return stillValid(settings);
        }
        candidate = MiningTargetFinder.find(mob, settings, block -> plugin.protection().canChange(block, mob)
                && !isProtectedMobBlock(block, settings));
        nextScanTick = ticks + SCAN_INTERVAL_TICKS;
        return candidate != null;
    }

    @Override
    public boolean shouldStayActive() {
        MiningSettings settings = plugin.settings().mining();
        if (finished || !settings.enabled()) {
            return false;
        }
        if (!mob.isValid() || mob.isDead()) {
            return false;
        }
        if (breaking) {
            return true;
        }
        return stillValid(settings);
    }

    @Override
    public void start() {
        MiningSettings settings = plugin.settings().mining();
        if (candidate == null) {
            finished = true;
            return;
        }
        long ticks = mob.getTicksLived();
        boolean resume = elapsedTicks > 0
                && sessionPos != null
                && sessionPos.equals(candidate.pos())
                && sessionMaterial == candidate.material()
                && ticks - sessionPausedAt <= RESUME_WINDOW_TICKS;
        if (!resume) {
            elapsedTicks = 0;
        }
        sessionPos = candidate.pos();
        sessionMaterial = candidate.material();
        sessionPausedAt = ticks;
        lastProgress = -1.0F;
        breaking = false;
        finished = false;
        suppressVanillaNavigation();
        plugin.registerMiningSession(candidate.pos(), this);
        lastProgressTick = ticks;
        plugin.registry().profile(mob).setBusy(true);
        plugin.registry().profile(mob).setMining(true);   // 挖掘期间禁止放置
        plugin.registry().profile(mob).setLastStatus("挖掘 " + candidate.material() + " @ "
                + candidate.pos().x() + "," + candidate.pos().y() + "," + candidate.pos().z());
        plugin.feedback().hit(settings, candidate.pos());

        int interval = Math.max(1, settings.tickInterval());
        progressTask = plugin.schedulers().entityTimer(mob, interval, interval,
                this::progressTick, () -> progressTask = null);
    }

    @Override
    public void tick() {
        long ticks = mob.getTicksLived();
        // breaking 卡死看门狗：破坏任务既没有成功也没有失败回调（区域调度被丢弃、世界关闭等），
        // 超过 3 秒直接收尾，避免怪物一直占着 MOVE 标记不动。
        if (breaking) {
            if (ticks - breakingSince > STALL_TIMEOUT_TICKS) {
                MiningCandidate target = candidate;
                plugin.registry().profile(mob).setLastStatus("破坏任务超时，已放弃");
                if (target != null) {
                    finishBreak(target, false);
                } else {
                    breaking = false;
                    finished = true;
                }
            }
            return;
        }
        if (candidate == null) {
            return;
        }
        // 看门狗：挖掘任务本应在推进，若长时间没有进度（被别的插件取消、异常等），
        // 立刻收尾并进入重试冷却，避免怪物一直占着 MOVE 标记原地不动。
        if (progressTask != null && ticks - lastProgressTick > STALL_TIMEOUT_TICKS) {
            ScheduledTask task = progressTask;
            progressTask = null;
            if (task != null) {
                task.cancel();
            }
            MobProfile profile = plugin.registry().profile(mob);
            profile.setLastStatus("挖掘停滞，已放弃");
            profile.setMiningCooldownUntil(System.currentTimeMillis()
                    + plugin.settings().mining().retryCooldownMillis());
            abandonSession();
            finished = true;
            return;
        }
        mob.lookAt(candidate.pos().center());
    }

    @Override
    public void stop() {
        ScheduledTask task = progressTask;
        if (task != null) {
            task.cancel();
            progressTask = null;
        }
        if (candidate != null) {
            // 停止时清理视觉进度，避免裂纹残留。
            plugin.feedback().clear(mob, candidate.pos());
            plugin.unregisterMiningSession(candidate.pos());
            // 保留 candidate / elapsedTicks：同一方块再次激活时接着挖（见 start()）。
            sessionPausedAt = mob.getTicksLived();
        }
        lastProgress = -1.0F;
        breaking = false;
        finished = false;
        plugin.registry().profile(mob).setBusy(false);
        plugin.registry().profile(mob).setMining(false);
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

    private void suppressVanillaNavigation() {
        Pathfinder pathfinder = mob.getPathfinder();
        if (pathfinder != null) {
            pathfinder.stopPathfinding();
        }
    }

    /** 怪物自己放下的方块默认不挖，避免同伴刚搭好的桥 / 台阶被拆掉。 */
    private boolean isProtectedMobBlock(Block block, MiningSettings settings) {
        return settings.protectMobBlocks() && plugin.tempBlocks().isPlacedByMob(block);
    }

    private boolean stillValid(MiningSettings settings) {
        if (candidate == null) {
            return false;
        }
        // 与目标判定共用同一套“够得着”规则（含头顶那一格 +2），
        // 否则会出现“找到了可挖的头顶方块、却每次都判定无效 → 反复重来、永远挖不掉”。
        if (!MiningTargetFinder.withinReach(mob.getLocation(), candidate.pos(), settings)) {
            return false;
        }
        Block block = candidate.pos().block();
        if (block.getType() != candidate.material()) {
            return false;
        }
        if (isProtectedMobBlock(block, settings)) {
            return false;
        }
        return BlockSafety.canBreak(block, settings);
    }

    /** 进度推进（实体调度器，间隔 = tick-interval）。 */
    private void progressTick(ScheduledTask task) {
        MiningSettings settings = plugin.settings().mining();
        if (candidate == null || finished || breaking) {
            task.cancel();
            return;
        }
        if (!mob.isValid() || mob.isDead()) {
            task.cancel();
            abandonSession();
            finished = true;
            return;
        }
        if (!stillValid(settings)) {
            // 方块被换掉、超出距离或被保护：放弃本次挖掘（下次从 0 开始）。
            task.cancel();
            abandonSession();
            finished = true;
            return;
        }

        elapsedTicks += Math.max(1, settings.tickInterval());
        MiningCandidate target = candidate;
        float progress = Math.min(1.0F, elapsedTicks / (float) target.totalTicks());
        if (progress > lastProgress) {
            plugin.feedback().progress(settings, mob, target.pos(), progress);
            lastProgress = progress;
        }
        // 进度还在推进：刷新看门狗时间戳。
        // 少了这一行，任何超过 STALL_TIMEOUT_TICKS（3 秒）的挖掘都会被误判成“停滞”并掐断重来——
        // 黑曜石（木镐 150 s / 铁镐 50 s）、远古残骸这类硬方块尤其明显。
        lastProgressTick = mob.getTicksLived();
        if (elapsedTicks % Math.max(1, settings.soundInterval()) == 0) {
            plugin.feedback().hit(settings, target.pos());
        }
        if (elapsedTicks >= target.totalTicks()) {
            task.cancel();
            progressTask = null;
            breaking = true;
            // 记下进入“破坏待回执”状态的时刻，否则 tick() 里的卡死看门狗恒成立，
            // 会把刚提交的破坏任务立刻当成超时放弃（重试链基本走不到）。
            breakingSince = mob.getTicksLived();
            submitBreak(target);
        }
    }

    /** 放弃当前会话的进度（方块已被移除或被替换）。 */
    private void abandonSession() {
        elapsedTicks = 0;
        sessionPos = null;
        sessionMaterial = null;
        if (candidate != null) {
            plugin.unregisterMiningSession(candidate.pos());
        }
    }

    /** 当前挖掘进度（0.0 - 1.0），供玩家点击方块后立即重发裂纹使用。 */
    public float currentProgress() {
        if (candidate == null || candidate.totalTicks() <= 0) {
            return 0.0F;
        }
        return Math.min(1.0F, elapsedTicks / (float) candidate.totalTicks());
    }

    /**
     * 重新给附近玩家发送一次裂纹进度。
     *
     * <p>玩家自己点击（或停止点击）正在被挖的方块时，客户端会把它自己的破坏动画状态覆盖/清掉，
     * 表现为“进度被打回 0”。这里在下一次实体 tick 上重发当前进度，把显示纠正回来。</p>
     */
    public void reassertOverlaySoon() {
        plugin.schedulers().entity(mob, this::reassertOverlay);
    }

    private void reassertOverlay() {
        if (finished || breaking || candidate == null) {
            return;
        }
        MiningSettings settings = plugin.settings().mining();
        if (!settings.progressViewers()) {
            return;
        }
        if (!stillValid(settings)) {
            return;
        }
        plugin.feedback().progress(settings, mob, candidate.pos(), Math.max(0.01F, currentProgress()));
    }

    /** 把方块移除投递到目标位置的区域线程。 */
    private void submitBreak(MiningCandidate target) {
        BlockPos pos = target.pos();
        boolean queued = plugin.schedulers().runAtBlock(pos.world(), pos.x(), pos.y(), pos.z(), () -> {
            boolean success = false;
            try {
                Block block = pos.block();
                if (bodyStillMatches(block, target)
                        && BlockSafety.canBreak(block, plugin.settings().mining())
                        && plugin.canChange(block, mob, MobBlockChangeEvent.Action.BREAK)) {
                    success = plugin.breakBlock(block, mob);
                }
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "移除目标方块失败", throwable);
            }
            boolean result = success;
            // 切回实体线程更新状态，避免在区域线程写实体数据。
            plugin.schedulers().entity(mob, () -> finishBreak(target, result));
        });
        if (!queued) {
            retryBreakLater(target);
        }
    }

    /**
     * 目标位置暂时无法调度（区块未加载 / 区域任务队列不存在 / 世界正在关闭）时的重试。
     *
     * <p>只有重试次数用尽才真正放弃并进入冷却；否则保持进度，下一 tick 再试，
     * 避免“挖到 100% 却什么都没发生”以及怪物被 {@code breaking} 卡死。</p>
     */
    private void retryBreakLater(MiningCandidate target) {
        breakAttempts++;
        if (breakAttempts > MAX_BREAK_ATTEMPTS) {
            breakAttempts = 0;
            plugin.registry().profile(mob).setLastStatus("挖掘失败：目标区域不可操作");
            finishBreak(target, false);
            return;
        }
        breaking = true;
        breakingSince = mob.getTicksLived();
        plugin.schedulers().entityDelayed(mob, 1L, () -> {
            if (finished || candidate == null) {
                return;
            }
            submitBreak(candidate);
        });
    }

    private boolean bodyStillMatches(Block block, MiningCandidate target) {
        return block.getType() == target.material();
    }

    /** 方块移除结束后的收尾（实体线程）。 */
    private void finishBreak(MiningCandidate target, boolean success) {
        MiningSettings settings = plugin.settings().mining();
        plugin.feedback().clear(mob, target.pos());
        MobProfile profile = plugin.registry().profile(mob);
        if (success) {
            plugin.feedback().broke(settings, target.pos());
            profile.setMiningCooldownUntil(System.currentTimeMillis() + settings.cooldownMillis());
        } else {
            profile.setMiningCooldownUntil(System.currentTimeMillis() + settings.retryCooldownMillis());
        }
        breakAttempts = 0;
        abandonSession();
        breaking = false;
        finished = true;
    }
}
