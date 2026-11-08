package studio.liuye.mobinteract.ai;

import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.MobGoals;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import studio.liuye.mobinteract.MobInteractPlugin;

/**
 * 把自定义行为注册到人形怪物的原版目标选择器里。
 *
 * <p>只新增目标，不删除任何原版目标；靠“更高优先级 + 激活条件”实现仲裁，
 * 因此挖掘 / 放置结束后原版追击会自然恢复。</p>
 *
 * <p>注册必须发生在怪物所属区域的线程上（监听器已做线程切换）。</p>
 */
public final class GoalManager {

    /** 数值越小优先级越高：挖掘优先于放置。 */
    private static final int MINING_PRIORITY = 1;
    private static final int PLACEMENT_PRIORITY = 2;

    private final MobInteractPlugin plugin;
    private final GoalKey<Mob> miningKey;
    private final GoalKey<Mob> placementKey;
    /** 服务端完全没有 Mob Goals API（永久不可用，无需反复重试）。 */
    private volatile boolean unavailable;
    /** 只提示一次的“暂时挂不上”告警，避免每只怪刷屏。 */
    private volatile boolean transientWarned;

    public GoalManager(MobInteractPlugin plugin) {
        this.plugin = plugin;
        this.miningKey = GoalKey.of(Mob.class, new NamespacedKey(plugin, "mining"));
        this.placementKey = GoalKey.of(Mob.class, new NamespacedKey(plugin, "placement"));
    }

    public GoalKey<Mob> miningKey() {
        return miningKey;
    }

    public GoalKey<Mob> placementKey() {
        return placementKey;
    }

    /** 为一只符合配置的生物补挂行为（可重复调用，已有则不重复添加）。 */
    public void attach(Mob mob) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return;
        }
        if (unavailable) {
            return;
        }
        // 是否接管由配置决定：默认人形集合，或 mob-whitelist 里自定义的任何生物类型。
        var miningSettings = plugin.settings().mining();
        var placementSettings = plugin.settings().placement();
        boolean wantMining = miningSettings.enabled()
                && miningSettings.worldFilter().allows(mob.getWorld())
                && miningSettings.mobFilter().allows(mob);
        boolean wantPlacement = placementSettings.enabled()
                && placementSettings.worldFilter().allows(mob.getWorld())
                && placementSettings.mobFilter().allows(mob);
        if (!wantMining && !wantPlacement) {
            return;
        }
        try {
            MobGoals goals = Bukkit.getMobGoals();
            if (wantMining && !goals.hasGoal(mob, miningKey)) {
                goals.addGoal(mob, MINING_PRIORITY, new MiningGoal(plugin, mob, miningKey));
            }
            if (wantPlacement && !goals.hasGoal(mob, placementKey)) {
                goals.addGoal(mob, PLACEMENT_PRIORITY, new PlacementGoal(plugin, mob, placementKey));
            }
        } catch (UnsupportedOperationException | LinkageError exception) {
            // 服务端根本没有 Mob Goals 实现（或版本不匹配）：这是永久状态，只提示一次。
            unavailable = true;
            plugin.getLogger().warning("当前服务端不支持 Paper Mob Goals API，怪物行为不会生效："
                    + exception.getMessage());
        } catch (IllegalStateException exception) {
            /*
             * 这只怪物此刻挂不上（区块卸载中、实体正要移除、线程不对等）。
             * 这类失败是暂时的，绝不能像以前那样把整个运行期锁死：
             * 否则第一次失败之后所有怪物都不再挂行为，只有 reload 插件才恢复。
             */
            if (!transientWarned) {
                transientWarned = true;
                plugin.getLogger().warning("暂时无法为怪物挂载行为，将在后续怪物上重试："
                        + exception.getMessage());
            }
        }
    }

    /** 当前服务端是否完全没有 Mob Goals API。 */
    public boolean unavailable() {
        return unavailable;
    }
}
