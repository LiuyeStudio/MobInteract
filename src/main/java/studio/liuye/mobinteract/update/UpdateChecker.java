package studio.liuye.mobinteract.update;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.config.UpdateSettings;

/**
 * 检查更新：启动后异步访问 GitHub Releases，比对本插件版本。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>不卡主线程</b>：全部走 {@link AsyncScheduler}（Paper 与 Folia 都提供），
 *       网络读写永远不在区域线程上发生；</li>
 *   <li><b>失败即静默</b>：连不上 / 超时 / 404 只记录一行 DEBUG（{@code update.yml} 的
 *       {@code debug: true} 时可见），不会在控制台刷红字，也不会影响插件其他功能；</li>
 *   <li><b>只读公开接口</b>：不发送服务器地址、玩家信息或任何标识，只带一个 User-Agent；</li>
 *   <li><b>可随时关掉</b>：{@code update.yml} 的 {@code enabled: false} 之后不再发起任何请求。</li>
 * </ul>
 */
public final class UpdateChecker {

    /** 能收到更新提醒的权限（与 /mobinteract 管理权限一致）。 */
    public static final String PERMISSION = "mobinteract.admin";

    /** 一次检查的结果类型。 */
    public enum Status {
        /** 检查更新被关闭，没有发起请求。 */
        DISABLED,
        /** 已是最新，或远端没有可用的正式版本。 */
        UP_TO_DATE,
        /** 远端有比当前更新的版本。 */
        AVAILABLE,
        /** 请求或解析失败。 */
        FAILED
    }

    /**
     * 一次检查的结果快照。
     *
     * @param status 结果类型
     * @param current 本插件当前版本
     * @param release 远端版本；只有 {@link Status#AVAILABLE} 时非空
     * @param detail 失败原因（HTTP 状态码 / 异常摘要），其余情况为空串
     */
    public record Result(Status status, String current, GitHubRelease release, String detail) {

        static Result disabled(String current) {
            return new Result(Status.DISABLED, current, null, "");
        }

        static Result upToDate(String current) {
            return new Result(Status.UP_TO_DATE, current, null, "");
        }

        static Result available(String current, GitHubRelease release) {
            return new Result(Status.AVAILABLE, current, release, "");
        }

        static Result failed(String current, String detail) {
            return new Result(Status.FAILED, current, null, detail == null ? "" : detail);
        }
    }

    private final MobInteractPlugin plugin;

    private final String currentVersion;

    private volatile UpdateSettings settings = UpdateSettings.defaults();

    private volatile Result lastResult;

    private volatile ScheduledTask scheduled;

    /** 同一次检查不并发跑两遍（自动检查与 /mobinteract update 撞车时）。 */
    private final AtomicBoolean running = new AtomicBoolean();

    public UpdateChecker(MobInteractPlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = readVersion(plugin);
    }

    /** 本插件当前版本，来自 plugin.yml 的 {@code version}。 */
    public String currentVersion() {
        return currentVersion;
    }

    /** 应用新配置（{@code /mobinteract reload} 时调用），不会自己重启调度。 */
    public void configure(UpdateSettings settings) {
        this.settings = settings == null ? UpdateSettings.defaults() : settings;
    }

    public UpdateSettings settings() {
        return settings;
    }

    /** 最近一次检查的结果；从未检查过时为 {@code null}。 */
    public Result lastResult() {
        return lastResult;
    }

    /** 是否已经查到“有更新”。 */
    public boolean hasUpdate() {
        Result result = lastResult;
        return result != null && result.status() == Status.AVAILABLE;
    }

    /** 按当前配置启动定时检查：先取消旧任务，再按 {@code first-delay-seconds} / {@code interval-hours} 排新任务。 */
    public void start() {
        stop();
        UpdateSettings current = settings;
        if (!current.enabled()) {
            debug("update.yml 的 enabled 为 false，不发起任何检查");
            return;
        }
        try {
            AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
            long delaySeconds = Math.max(0, current.firstDelaySeconds());
            if (current.onceOnly()) {
                scheduled = scheduler.runDelayed(plugin, task -> check(null), delaySeconds, TimeUnit.SECONDS);
            } else {
                long periodSeconds = Math.max(1L, (long) current.intervalHours() * 3600L);
                scheduled = scheduler.runAtFixedRate(plugin, task -> check(null),
                        delaySeconds, periodSeconds, TimeUnit.SECONDS);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "无法启动检查更新任务（本功能已跳过）", throwable);
        }
    }

    /** 取消定时检查（插件卸载 / 重载前调用）。 */
    public void stop() {
        ScheduledTask task = scheduled;
        scheduled = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 任务已经结束或插件正在关闭：无需处理。
            }
        }
    }

    /**
     * 立刻检查一次。
     *
     * <p>回调在<b>异步线程</b>上执行，调用方若要给玩家发消息，
     * 需要通过 {@code plugin.schedulers().entity(...)} 切回实体线程。</p>
     */
    public void checkNow(Consumer<Result> callback) {
        UpdateSettings current = settings;
        if (!current.enabled()) {
            callback.accept(Result.disabled(currentVersion));
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> check(callback));
        } catch (Throwable throwable) {
            Result result = Result.failed(currentVersion, throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
            lastResult = result;
            callback.accept(result);
        }
    }

    // ------------------------------------------------------------------
    // 内部：请求 + 比对 + 通知
    // ------------------------------------------------------------------

    private void check(Consumer<Result> callback) {
        UpdateSettings current = settings;
        if (!current.enabled()) {
            if (callback != null) {
                callback.accept(Result.disabled(currentVersion));
            }
            return;
        }
        if (!running.compareAndSet(false, true)) {
            // 已有一次检查在跑：直接把上次结果回给调用方，不重复请求。
            if (callback != null) {
                Result previous = lastResult;
                callback.accept(previous != null ? previous : Result.upToDate(currentVersion));
            }
            return;
        }
        Result result;
        try {
            result = fetch();
        } catch (Throwable throwable) {
            result = Result.failed(currentVersion, describe(throwable));
            debug("检查失败：" + describe(throwable));
        } finally {
            running.set(false);
        }
        lastResult = result;
        announce(result);
        if (callback != null) {
            callback.accept(result);
        }
    }

    private Result fetch() throws IOException {
        UpdateSettings current = settings;
        String endpoint = "https://api.github.com/repos/" + current.repository() + "/releases?per_page=10";
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        int timeoutMillis = Math.max(1, current.timeoutSeconds()) * 1000;
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent",
                    "MobInteract/" + currentVersion + " (+https://github.com/" + current.repository() + ")");
            int code = connection.getResponseCode();
            debug("GET " + endpoint + " -> HTTP " + code);
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.failed(currentVersion, "HTTP " + code);
            }
            String body;
            try (InputStream stream = connection.getInputStream()) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            GitHubRelease release = GitHubRelease.pick(body, current.includePrerelease());
            if (release == null) {
                debug("远端没有可用版本，按已是最新处理");
                return Result.upToDate(currentVersion);
            }
            debug("远端版本 " + release.tag() + "，当前版本 " + currentVersion);
            if (VersionTags.isNewer(release.tag(), currentVersion)) {
                return Result.available(currentVersion, release);
            }
            return Result.upToDate(currentVersion);
        } finally {
            connection.disconnect();
        }
    }

    /** 控制台与在线管理员的提醒。发现更新时最多各提醒一次（同一版本）。 */
    private void announce(Result result) {
        if (result.status() != Status.AVAILABLE || result.release() == null) {
            return;
        }
        UpdateSettings current = settings;
        GitHubRelease release = result.release();
        if (current.console()) {
            plugin.getLogger().info("发现新版本 " + release.tag() + "（当前 " + result.current() + "），下载："
                    + release.url());
            plugin.getLogger().info("可在 update.yml 中关闭检查更新，或用 /mobinteract update 手动检查。");
        }
        if (current.notifyOnlineAdmins()) {
            List<String> lines = plugin.messages().getList("update.notify",
                    "current", result.current(), "latest", release.tag(), "url", release.url());
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission(PERMISSION)) {
                    continue;
                }
                // 私聊必须回到玩家所属区域的线程上发，异步线程直接 sendMessage 在 Folia 下不安全。
                plugin.schedulers().entity(player, () -> {
                    if (player.isOnline()) {
                        lines.forEach(player::sendMessage);
                    }
                });
            }
        }
    }

    private String describe(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private void debug(String message) {
        if (settings.debug()) {
            plugin.getLogger().info("[update] " + message);
        }
    }

    private static String readVersion(MobInteractPlugin plugin) {
        try {
            String version = plugin.getPluginMeta().getVersion();
            if (version != null && !version.isBlank()) {
                return version.trim();
            }
        } catch (Throwable ignored) {
            // 老服务端没有 getPluginMeta()：回退到描述文件。
        }
        try {
            return plugin.getDescription().getVersion();
        } catch (Throwable ignored) {
            return "0";
        }
    }
}
