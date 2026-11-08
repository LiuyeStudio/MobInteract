package studio.liuye.mobinteract;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Mob;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import studio.liuye.mobinteract.ai.GoalManager;
import studio.liuye.mobinteract.ai.MiningGoal;
import studio.liuye.mobinteract.api.MobBlockChangeEvent;
import studio.liuye.mobinteract.command.MobInteractCommand;
import studio.liuye.mobinteract.config.ConfigManager;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.config.Settings;
import studio.liuye.mobinteract.listener.MobLifecycleListener;
import studio.liuye.mobinteract.message.Messages;
import studio.liuye.mobinteract.mining.MiningFeedback;
import studio.liuye.mobinteract.mining.MiningTool;
import studio.liuye.mobinteract.mining.PickaxeService;
import studio.liuye.mobinteract.mob.MobRegistry;
import studio.liuye.mobinteract.placement.CrowdGuard;
import studio.liuye.mobinteract.placement.HeldItemService;
import studio.liuye.mobinteract.placement.PlacementExecutor;
import studio.liuye.mobinteract.placement.TempBlockRegistry;
import studio.liuye.mobinteract.pickup.ItemPickupService;
import studio.liuye.mobinteract.safety.ProtectionService;
import studio.liuye.mobinteract.update.UpdateChecker;
import studio.liuye.mobinteract.update.UpdateJoinListener;
import studio.liuye.mobinteract.util.BlockPos;
import studio.liuye.mobinteract.util.Schedulers;

/**
 * MobInteract 插件入口。
 *
 * <p>只使用跨版本公开 API：RegionScheduler / EntityScheduler / GlobalRegionScheduler、
 * Mob Goals、BlockData、Material#getHardness 等，没有任何 CraftBukkit 或 NMS 依赖。</p>
 */
public final class MobInteractPlugin extends JavaPlugin {

    private final Random random = new Random();

    /** 正在被挖掘的方块 → 负责该方块的挖掘行为（用于玩家点击后立即纠正裂纹显示）。 */
    private final Map<BlockPos, MiningGoal> miningSessions = new ConcurrentHashMap<>();

    private Schedulers schedulers;
    private ConfigManager configManager;
    private Messages messages;
    private MobRegistry registry;
    private ProtectionService protection;
    private GoalManager goalManager;
    private TempBlockRegistry tempBlocks;
    private MiningFeedback feedback;
    private PlacementExecutor placementExecutor;
    private HeldItemService heldItemService;
    private CrowdGuard crowdGuard;
    private PickaxeService pickaxeService;
    private ItemPickupService pickupService;
    private studio.liuye.mobinteract.targeting.PlayerPriorityService playerPriority;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        schedulers = new Schedulers(this);

        configManager = new ConfigManager(this);
        configManager.saveDefaultsIfMissing();
        Settings settings;
        try {
            settings = configManager.reload();
        } catch (RuntimeException exception) {
            getLogger().severe("读取配置失败，插件将使用内置默认值运行：" + exception.getMessage());
            configManager.saveDefaultsIfMissing();
            settings = configManager.reload();
        }

        // message.yml：所有发给玩家的文本。行为配置之外单独一份，同样可热重载。
        messages = new Messages(this);
        messages.saveDefaultsIfMissing();
        try {
            messages.reload();
        } catch (RuntimeException exception) {
            getLogger().severe("读取 message.yml 失败，将使用内置文本：" + exception.getMessage());
        }

        registry = new MobRegistry();
        protection = new ProtectionService();
        protection.detectBlockLocker(this);
        goalManager = new GoalManager(this);
        tempBlocks = new TempBlockRegistry(this);
        feedback = new MiningFeedback();
        placementExecutor = new PlacementExecutor(this);
        heldItemService = new HeldItemService(this);
        crowdGuard = new CrowdGuard(this);
        pickaxeService = new PickaxeService(this);
        pickupService = new ItemPickupService(this);
        playerPriority = new studio.liuye.mobinteract.targeting.PlayerPriorityService(this);

        getServer().getPluginManager().registerEvents(new MobLifecycleListener(this), this);

        // 检查更新：只读 GitHub 的公开 Release 列表，异步执行，失败静默。
        updateChecker = new UpdateChecker(this);
        updateChecker.configure(settings.update());
        updateChecker.start();
        getServer().getPluginManager().registerEvents(new UpdateJoinListener(this), this);

        // 上次卸载 / 崩溃时没删干净的怪物方块：启动后兜底清一遍。
        tempBlocks.loadJournalAndClean();
        // 到期清扫器：每 20 tick 扫一次到期的临时方块（不依赖一次性延迟任务）。
        tempBlocks.startSweeper();

        PluginCommand command = getCommand("mobinteract");
        if (command != null) {
            MobInteractCommand executor = new MobInteractCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("MobInteract 已启用：挖掘=" + settings.mining().enabled()
                + "，放置=" + settings.placement().enabled()
                + "，可放置条目=" + settings.placement().entries().size());
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            // 先取消异步检查任务，避免插件卸载后还往日志里写东西。
            updateChecker.stop();
        }
        if (tempBlocks != null) {
            // 卸载时把怪物放下的方块全部清掉（插件卸载 / 被替换 / 关服都会走到这里）。
            // 先把清单落盘：万一关服过程中被强杀、或 Folia 区域线程已不再执行任务，
            // 下次启动会再兜底清一次。
            int tracked = tempBlocks.size();
            tempBlocks.saveJournal();
            int removed = tempBlocks.removeAllNow();
            if (removed >= tracked) {
                // 全删干净：清单也没必要留着了。
                tempBlocks.clearJournal();
            }
            getLogger().info("插件卸载：清理怪物放置的方块 " + removed + "/" + tracked + " 个");
        }
        if (registry != null) {
            registry.clear();
        }
        miningSessions.clear();
    }

    // ------------------------------------------------------------------
    // 组件访问
    // ------------------------------------------------------------------

    public Schedulers schedulers() {
        return schedulers;
    }

    public Settings settings() {
        return configManager.current();
    }

    /** 玩家可见文本（message.yml）。 */
    public Messages messages() {
        return messages;
    }

    /** 检查更新（GitHub Releases）。 */
    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    /** 重载全部配置：mining / placement / equip / targeting / update 与 message.yml。 */
    public void reloadSettings() {
        Settings refreshed = configManager.reload();
        messages.reload();
        if (updateChecker != null) {
            updateChecker.configure(refreshed.update());
            // 重新排检查任务：改间隔、临时关掉再打开都能立刻生效。
            updateChecker.start();
        }
        // 已经在世界里的怪不会自己重读配置：它们的「出生装备闸门」「背包」「本次追击的放置计数」
        // 都记在 MobProfile 上，而这是按 UUID 建的一次性状态。旧实现在 reload 之后，
        // 已经生成的怪继续按旧配置行动——服主改完 mining.yml 进游戏一看“没反应”，
        // 只能重启服务端。这里直接清空档案，让每只怪下一次 tick 重新建档、按新配置重新掷骰。
        // 代价：没有存档包袱的那部分状态（背包里还没放出去的方块、临时冷却）会重置，
        // 换来的是一句“reload 立即生效”确实成立。
        registry.clear();
    }

    public MobRegistry registry() {
        return registry;
    }

    public ProtectionService protection() {
        return protection;
    }

    public GoalManager goalManager() {
        return goalManager;
    }

    public TempBlockRegistry tempBlocks() {
        return tempBlocks;
    }

    public MiningFeedback feedback() {
        return feedback;
    }

    public PlacementExecutor placementExecutor() {
        return placementExecutor;
    }

    public PickaxeService pickaxeService() {
        return pickaxeService;
    }

    public HeldItemService heldItems() {
        return heldItemService;
    }

    public CrowdGuard crowdGuard() {
        return crowdGuard;
    }

    /** 主动拾取地上的物品（方块 / 镐子）。 */
    /** 索敌偏好（优先追杀玩家）。 */
    public studio.liuye.mobinteract.targeting.PlayerPriorityService playerPriority() {
        return playerPriority;
    }

    public ItemPickupService pickup() {
        return pickupService;
    }

    public Random random() {
        return random;
    }

    // ------------------------------------------------------------------
    // 挖掘会话（供 BlockDamageEvent 纠正显示）
    // ------------------------------------------------------------------

    public void registerMiningSession(BlockPos pos, MiningGoal goal) {
        if (pos != null && goal != null) {
            miningSessions.put(pos, goal);
        }
    }

    public void unregisterMiningSession(BlockPos pos) {
        if (pos != null) {
            miningSessions.remove(pos);
        }
    }

    /** 该方块当前是否正被某个怪物挖掘；没有则返回 {@code null}。 */
    public MiningGoal miningSession(BlockPos pos) {
        return pos == null ? null : miningSessions.get(pos);
    }

    public int miningSessionCount() {
        return miningSessions.size();
    }

    // ------------------------------------------------------------------
    // 方块修改入口
    // ------------------------------------------------------------------

    /**
     * 方块修改前的最终校验：先问保护区判定，再触发可取消的 {@link MobBlockChangeEvent}。
     *
     * <p>必须在目标方块所在区域的线程上调用。</p>
     */
    public boolean canChange(Block block, Mob mob, MobBlockChangeEvent.Action action) {
        if (!protection.canChange(block, mob)) {
            return false;
        }
        MobBlockChangeEvent event = new MobBlockChangeEvent(mob, block, action);
        getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /**
     * 按配置挖掉一个方块。
     *
     * <p>三件事按顺序做：</p>
     * <ol>
     *   <li>先触发一次 {@link BlockBreakEvent}（玩家为 {@code null}，表示非玩家破坏），
     *       让 WorldGuard / GriefPrevention / CoreProtect 这类插件能看见、能拦截、能记日志——
     *       否则怪物挖方块等于绕过整个保护体系；</li>
     *   <li>{@code drop-items} 打开时用怪物手里的镐子结算掉落（空手结算石头类方块是零掉落，
     *       等于白开了这个开关）；</li>
     *   <li>否则直接置为空气（默认行为，不掉任何东西，杜绝刷物品）。</li>
     * </ol>
     *
     * <p>必须在方块所在区域的线程上调用。</p>
     *
     * @param block 目标方块
     * @param mob   发起破坏的怪物，可为 {@code null}（没有来源时按空手处理）
     * @return true 表示方块已被移除
     */
    public boolean breakBlock(Block block, Mob mob) {
        if (block == null || block.getType().isAir()) {
            return false;
        }
        if (!fireBlockBreakEvent(block)) {
            return false;
        }
        MiningSettings settings = settings().mining();
        if (settings.dropItems()) {
            Material pickaxe = mob == null ? null : MiningTool.heldPickaxe(mob);
            ItemStack tool = pickaxe == null ? new ItemStack(Material.AIR) : new ItemStack(pickaxe);
            return block.breakNaturally(tool);
        }
        block.setBlockData(Material.AIR.createBlockData(), true);
        return true;
    }

    /**
     * 触发 {@link BlockBreakEvent}，返回“是否允许破坏”。
     *
     * <p>玩家传 {@code null}：BlockBreakEvent 的约定是“谁挖的”，怪物挖矿没有玩家主体。
     * 少数插件会直接对 {@code getPlayer()} 取值而抛异常，所以这里把整个调用包在 try/catch 里，
     * 出错时按“未取消”继续——保护插件自身的 bug 不该让怪物彻底不能挖方块。</p>
     */
    private boolean fireBlockBreakEvent(Block block) {
        BlockBreakEvent event;
        try {
            event = new BlockBreakEvent(block, null);
        } catch (Throwable throwable) {
            // 某些版本对 null 玩家有额外校验：拿不到事件就退化成“不通知”，不阻断破坏。
            return true;
        }
        try {
            getServer().getPluginManager().callEvent(event);
        } catch (Throwable throwable) {
            getLogger().log(Level.FINE, "BlockBreakEvent 处理时出错（已忽略，按未拦截处理）", throwable);
            return true;
        }
        return !event.isCancelled();
    }
}
