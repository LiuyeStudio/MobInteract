package studio.liuye.mobinteract.targeting;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.config.TargetingSettings;

/**
 * “优先追杀玩家”。
 *
 * <p>由实体定时任务按 {@code interval-ticks} 调用：只要附近有可追的玩家，就把目标切成最近的玩家。
 * 已经在追玩家的怪物不会来回切目标（避免左右横跳）。</p>
 *
 * <p>为什么不用 Mob Goal：这里只改 {@code setTarget}，不动原版寻路，交给原版目标仇恨逻辑继续跑，
 * 因此不会和已有的挖掘 / 放置 Goal 抢 MOVE 标记。</p>
 */
public final class PlayerPriorityService {

    private final MobInteractPlugin plugin;

    public PlayerPriorityService(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 检查一次并在需要时切换目标。
     *
     * @param mob 被插件接管的怪物（必须在它自己的区域线程上调用）
     */
    public void tick(Mob mob) {
        TargetingSettings settings = plugin.settings().targeting();
        if (!settings.enabled() || mob == null || !mob.isValid() || mob.isDead()) {
            return;
        }
        LivingEntity current = mob.getTarget();
        if (current instanceof Player currentPlayer && chaseable(currentPlayer)) {
            // 已经在追玩家：不来回换，咬住不放。
            return;
        }

        Player best = nearestPlayer(mob, settings);
        if (best == null) {
            if (!settings.keepVillagerTarget() && current != null && !(current instanceof Player)) {
                // 附近没有玩家，按配置清空原目标（默认保留，交给原版）。
                mob.setTarget(null);
                plugin.registry().profile(mob).setLastStatus("附近没有玩家，放弃当前目标");
            }
            return;
        }
        mob.setTarget(best);
        plugin.registry().profile(mob).setLastStatus("优先追杀玩家 " + best.getName()
                + "（" + Math.round(best.getLocation().distance(mob.getLocation()) * 10.0D) / 10.0D + " 格）");
    }

    /** 附近最近的可追玩家；没有则 {@code null}。 */
    private Player nearestPlayer(Mob mob, TargetingSettings settings) {
        List<PlayerPriority.Candidate> candidates = new ArrayList<>();
        List<Player> players = new ArrayList<>();
        try {
            for (Player player : mob.getWorld().getNearbyPlayers(mob.getLocation(), settings.radius())) {
                if (!chaseable(player)) {
                    continue;
                }
                if (settings.requireLineOfSight() && !mob.hasLineOfSight(player)) {
                    continue;
                }
                candidates.add(new PlayerPriority.Candidate(player.getUniqueId(),
                        player.getX(), player.getY(), player.getZ()));
                players.add(player);
            }
        } catch (Throwable throwable) {
            // 区域线程 / 世界关闭等极端情况：这次跳过，下一次定时任务再试。
            return null;
        }
        PlayerPriority.Candidate from = new PlayerPriority.Candidate(mob.getUniqueId(),
                mob.getX(), mob.getY(), mob.getZ());
        PlayerPriority.Candidate picked = PlayerPriority.pick(from, candidates, settings.radius());
        if (picked == null) {
            return null;
        }
        for (Player player : players) {
            if (player.getUniqueId().equals(picked.id())) {
                return player;
            }
        }
        return null;
    }

    /** 这个玩家是否值得追（排除创造 / 旁观 / 已死亡 / 不可见）。 */
    private boolean chaseable(Player player) {
        if (player == null || !player.isValid() || player.isDead()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }
}
