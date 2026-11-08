package studio.liuye.mobinteract.listener;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Material;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.ai.MiningGoal;
import studio.liuye.mobinteract.config.EquipSettings;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.config.Settings;
import studio.liuye.mobinteract.util.BlockPos;

/** 生物进出世界时的行为注册与状态回收。 */
public final class MobLifecycleListener implements Listener {

    private final MobInteractPlugin plugin;

    public MobLifecycleListener(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 生物进入世界（生成 / 区块加载）时为它补挂行为。
     *
     * <p>是否接管由配置决定：默认人形集合，或 {@code mob-whitelist} 里自定义的任何生物类型
     * （例如 {@code IRON_GOLEM}、{@code WOLF}）。这里先做一次快速过滤，避免给无关生物
     * 排调度任务；真正挂载时 {@code GoalManager} 会再按各自的配置判定一次。</p>
     *
     * <p>Mob Goals 只能在实体所属区域的线程上修改，所以这里切到实体调度器。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Settings settings = plugin.settings();
        PlacementSettings placement = settings.placement();
        // 先按“世界 + 生物”双重筛选，避免在未启用的世界里白排任务。
        boolean watched = (settings.mining().enabled()
                    && settings.mining().worldFilter().allows(mob.getWorld())
                    && settings.mining().mobFilter().allows(mob))
                || (placement.enabled()
                    && placement.worldFilter().allows(mob.getWorld())
                    && placement.mobFilter().allows(mob));
        if (!watched) {
            return;
        }
        plugin.schedulers().entity(mob, () -> {
            // 出生装备总闸门：没过闸门就既不给镐子也不给方块，
            // 保证“带镐子 + 带方块”的怪物比例加起来不超过 equip.yml 里配置的概率。
            // 骰子结果记在 MobProfile 上（每只怪只掷一次），背包那条路径也会读它。
            EquipSettings equip = settings.equip();
            boolean equipped = plugin.registry().profile(mob)
                    .rollEquip(equip.chanceFor(mob.getWorld().getName()), plugin.random());
            if (equipped) {
                plugin.pickaxeService().maybeEquip(mob);
            }
            // 有背包的怪物一进入世界就把方块拿在手上（副手，数量 = 库存）。
            // decideBackpack 内部也会检查闸门，所以没过闸门的怪不会在这里拿到方块。
            if (placement.enabled() && placement.worldFilter().allows(mob.getWorld())
                    && placement.mobFilter().allows(mob)) {
                plugin.registry().profile(mob).decideBackpack(placement, plugin.random());
                plugin.heldItems().sync(mob);
            }
            plugin.goalManager().attach(mob);
            // 定期检查是否被同伴挤住：挤住时给原版寻路设置侧向绕行点，
            // 避免所有怪物排成一列互相顶（实体被移除后任务自动失效）。
            plugin.schedulers().entityTimer(mob, 40L, 40L, task -> tickDetour(mob), null);
            // 优先追杀玩家：按 targeting.interval-ticks 定期检查，附近有玩家就把目标切成玩家。
            if (settings.targeting().enabled()) {
                long interval = Math.max(1L, settings.targeting().intervalTicks());
                plugin.schedulers().entityTimer(mob, interval, interval,
                        task -> plugin.playerPriority().tick(mob), null);
            }
            // 主动拾取地上的方块 / 镐子（每秒看一次，捡到就记进背包或换到主手）。
            if (placement.pickupEnabled()) {
                plugin.schedulers().entityTimer(mob, 20L, 20L,
                        task -> plugin.pickup().tick(mob), null);
            }
        });
    }

    /** 每 2 秒检查一次拥挤：被挤住就让它绕路。 */
    private void tickDetour(Mob mob) {
        if (!mob.isValid() || mob.isDead() || mob.getTarget() == null) {
            return;
        }
        PlacementSettings settings = plugin.settings().placement();
        if (!settings.enabled() || !settings.spreadCrowd()) {
            return;
        }
        if (!settings.worldFilter().allows(mob.getWorld()) || !settings.mobFilter().allows(mob)) {
            return;
        }
        var profile = plugin.registry().find(mob.getUniqueId());
        if (profile == null || profile.busy()) {
            return;
        }
        double radius = Math.max(1.6D, settings.spacingRadius() + 0.6D);
        plugin.crowdGuard().detour(mob, radius, 1500L);
    }

    /**
     * 原版把掉落物捡进怪物手里时接管掉：方块进虚拟背包、镐子换到主手。
     *
     * <p>不接管的话，僵尸会把地上的圆石塞进主手，方块背包永远拿不到这些方块，
     * 副手的库存数量也就对不上了。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        PlacementSettings settings = plugin.settings().placement();
        if (!settings.enabled() || !settings.pickupEnabled()) {
            return;
        }
        if (!settings.worldFilter().allows(mob.getWorld()) || !settings.mobFilter().allows(mob)) {
            return;
        }
        Material material = event.getItem().getItemStack().getType();
        if (!plugin.pickup().wants(mob, material, settings)) {
            return;
        }
        event.setCancelled(true);
        plugin.pickup().absorb(mob, event.getItem(), settings);
    }

    /** 实体被移除时回收运行期状态，避免注册表长期增长。 */
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            plugin.registry().remove(mob.getUniqueId());
        }
    }

    /**
     * 玩家开始挖一个“正被怪物挖掘”的方块时，客户端会用它自己的破坏动画覆盖掉怪物那一份，
     * 表现为进度被打回 0。这里在怪物下一次实体 tick 上重发当前进度，把显示纠正回来。
     *
     * <p>挖掘本身的状态由 {@code MiningGoal} 自己维护，玩家的点击不会改变怪物的进度。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        MiningGoal goal = plugin.miningSession(BlockPos.of(event.getBlock()));
        if (goal != null) {
            goal.reassertOverlaySoon();
        }
    }
}
