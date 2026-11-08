package studio.liuye.mobinteract;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Mob;
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
     * <p>默认不产生掉落物（防止刷物品）；只有配置显式开启 {@code drop-items} 时才走
     * {@code breakNaturally()}。必须在方块所在区域的线程上调用。</p>
     *
     * @return true 表示方块已被移除
     */
    public boolean breakBlock(Block block) {
        MiningSettings settings = settings().mining();
        if (settings.dropItems()) {
            return block.breakNaturally();
        }
        if (block.getType().isAir()) {
            return false;
        }
        block.setBlockData(Material.AIR.createBlockData(), true);
        return true;
    }
}
