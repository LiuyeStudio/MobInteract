package studio.liuye.mobinteract.update;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import studio.liuye.mobinteract.MobInteractPlugin;

/**
 * 管理员上线时补一次更新提醒。
 *
 * <p>服务器常年不重启，只在启动后检查一次的话，很多人永远看不到提示。
 * 这里在玩家进服时把“最近一次检查到的结果”拿来用（不会因此多请求一次 GitHub），
 * 每名玩家每次开服只提醒一次。</p>
 */
public final class UpdateJoinListener implements Listener {

    /** 已经提醒过的玩家；重载配置时不清空，避免反复刷同一句。 */
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();

    private final MobInteractPlugin plugin;

    public UpdateJoinListener(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UpdateChecker checker = plugin.updateChecker();
        if (checker == null || !checker.settings().notifyOnJoin()) {
            return;
        }
        UpdateChecker.Result result = checker.lastResult();
        if (result == null || result.status() != UpdateChecker.Status.AVAILABLE || result.release() == null) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        if (!event.getPlayer().hasPermission(UpdateChecker.PERMISSION) || !notified.add(id)) {
            return;
        }
        var lines = plugin.messages().getList("update.join-notice",
                "current", result.current(),
                "latest", result.release().tag(),
                "url", result.release().url());
        plugin.schedulers().entity(event.getPlayer(), () -> {
            if (event.getPlayer().isOnline()) {
                lines.forEach(event.getPlayer()::sendMessage);
            }
        });
    }
}
