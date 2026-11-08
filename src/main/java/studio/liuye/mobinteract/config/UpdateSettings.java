package studio.liuye.mobinteract.config;

/**
 * {@code update.yml}：检查更新的开关与参数。
 *
 * <p>这里只放“要不要检查、多久检查一次、发现了之后怎么提醒”这类设置；
 * 真正的网络请求与版本比对在 {@code studio.liuye.mobinteract.update} 包里。</p>
 *
 * <p>不可变记录，随 {@link Settings} 一起整体替换，区域线程读到的永远是同一份快照。</p>
 */
public record UpdateSettings(boolean enabled, String repository, int firstDelaySeconds, int intervalHours,
        boolean console, boolean notifyOnlineAdmins, boolean notifyOnJoin, boolean includePrerelease,
        int timeoutSeconds, boolean debug) {

    /** 内置默认值：开着检查、每天 4 次、控制台 + 管理员都会收到提示。 */
    public static UpdateSettings defaults() {
        return new UpdateSettings(true, "LiuyeStudio/MobInteract", 60, 6, true, true, true, false, 8, false);
    }

    /** 关掉检查更新（配置文件里 {@code enabled: false} 时使用，等价于默认值但总开关为关）。 */
    public UpdateSettings disabled() {
        return new UpdateSettings(false, repository, firstDelaySeconds, intervalHours, console,
                notifyOnlineAdmins, notifyOnJoin, includePrerelease, timeoutSeconds, debug);
    }

    /** 是否只检查一次（{@code interval-hours: 0}）。 */
    public boolean onceOnly() {
        return intervalHours <= 0;
    }
}
