package studio.liuye.mobinteract.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Folia 线程模型的调度封装。
 *
 * <p>所有调度都走公开 API：{@code Bukkit#getRegionScheduler()}、
 * {@code Entity#getScheduler()} 与 {@code Bukkit#getGlobalRegionScheduler()}，
 * 不使用 {@code BukkitScheduler}，也不做任何跨线程直接访问。</p>
 *
 * <p>注意：RegionScheduler 的 (world, x, z) 参数是<b>区块坐标</b>
 * （API 里以 Location 为参数的默认方法会先做 {@code getBlockX() >> 4} / {@code getBlockZ() >> 4}）。
 * 因此本类对外提供的方法收的是<b>方块坐标</b>，提交前统一换算成区块坐标。</p>
 *
 * <p>另外，{@code RegionScheduler#execute} 在目标区块没有区域任务队列时（区块未加载、
 * 世界正在关闭、区域正在迁移）会直接抛 {@code IllegalStateException}。这类异常发生在
 * <b>提交任务时</b>而不是任务体里，所以这里统一捕获，并通过返回值告诉调用方“这次没提交成功”。</p>
 */
public final class Schedulers {

    /** 已经打印过的“区域不可用”提示，避免刷屏。 */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private final Plugin plugin;

    public Schedulers(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 在拥有该方块所在区域（region）的线程上执行一次任务。
     *
     * @return true 表示任务已提交；false 表示该位置当前无法调度（区块未加载 / 世界关闭等）
     */
    public boolean region(World world, int x, int z, Runnable task) {
        if (world == null) {
            return false;
        }
        try {
            // 注意：RegionScheduler 的 (world, x, z) 是<b>区块坐标</b>。
            // API 里以 Location 为参数的默认方法就是把 getBlockX()/getBlockZ() 各 >> 4 再传进来，
            // 所以这里的方法签名收“方块坐标”，提交前统一换算成区块坐标。
            Bukkit.getRegionScheduler().execute(plugin, world, x >> 4, z >> 4, wrap(task));
            return true;
        } catch (Throwable throwable) {
            reportOnce("region-schedule", world, x, z, throwable);
            return false;
        }
    }

    /** 在拥有该方块所在区域的线程上延迟执行（delay 单位 tick）；失败时返回 {@code null}。 */
    public ScheduledTask regionDelayed(World world, int x, int z, long delayTicks, Runnable task) {
        if (world == null) {
            return null;
        }
        try {
            return Bukkit.getRegionScheduler().runDelayed(plugin, world, x >> 4, z >> 4,
                    scheduled -> execute(task), Math.max(1L, delayTicks));
        } catch (Throwable throwable) {
            reportOnce("region-delayed", world, x, z, throwable);
            return null;
        }
    }

    /**
     * 修改某个方块时的推荐入口：如果当前线程已经拥有该位置（同一 region），直接执行；
     * 否则提交到该位置所在区域。
     *
     * @return true 表示已执行（或已成功提交），false 表示该位置当前不可操作
     */
    public boolean runAtBlock(World world, int x, int y, int z, Runnable task) {
        if (world == null) {
            return false;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Location location = new Location(world, x, y, z);
        try {
            if (Bukkit.isOwnedByCurrentRegion(location)) {
                execute(task);
                return true;
            }
        } catch (Throwable ignored) {
            // 不在区域线程上调用时该方法可能抛错，按需要调度处理。
        }
        return region(world, x, z, task);
    }

    /** 在实体所属区域的线程上执行一次任务；实体已被移除时静默丢弃。 */
    public void entity(Entity entity, Runnable task) {
        try {
            entity.getScheduler().run(plugin, scheduled -> execute(task), null);
        } catch (Throwable throwable) {
            reportOnce("entity-schedule", null, 0, 0, throwable);
        }
    }

    /** 在实体所属区域的线程上延迟执行（delay 单位 tick）。 */
    public ScheduledTask entityDelayed(Entity entity, long delayTicks, Runnable task) {
        try {
            return entity.getScheduler().runDelayed(plugin, scheduled -> execute(task), null,
                    Math.max(1L, delayTicks));
        } catch (Throwable throwable) {
            reportOnce("entity-delayed", null, 0, 0, throwable);
            return null;
        }
    }

    /** 在实体线程上按固定周期执行；回调里可以用传入的 task 取消自己。 */
    public ScheduledTask entityTimer(Entity entity, long delayTicks, long periodTicks,
            java.util.function.Consumer<ScheduledTask> task, Runnable retired) {
        try {
            return entity.getScheduler().runAtFixedRate(plugin, task, retired,
                    Math.max(0L, delayTicks), Math.max(1L, periodTicks));
        } catch (Throwable throwable) {
            reportOnce("entity-timer", null, 0, 0, throwable);
            return null;
        }
    }

    /** 全局区域线程（插件级初始化、配置重载等）。 */
    public void global(Runnable task) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, wrap(task));
        } catch (Throwable throwable) {
            reportOnce("global-schedule", null, 0, 0, throwable);
        }
    }

    /**
     * 在全局区域线程上按固定周期执行（例如临时方块到期清扫）。
     *
     * @return 任务句柄；提交失败时返回 {@code null}
     */
    public ScheduledTask globalTimer(long periodTicks, Runnable task) {
        try {
            return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduled -> execute(task), Math.max(1L, periodTicks), Math.max(1L, periodTicks));
        } catch (Throwable throwable) {
            reportOnce("global-timer", null, 0, 0, throwable);
            return null;
        }
    }

    private Runnable wrap(Runnable task) {
        return () -> execute(task);
    }

    private void execute(Runnable task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "MobInteract 调度任务执行失败", throwable);
        }
    }

    /** 同一类调度失败只打印一次完整堆栈，后续降为 FINE，避免异常刷屏拖慢服务器。 */
    private void reportOnce(String kind, World world, int x, int z, Throwable throwable) {
        String key = kind + (world == null ? "" : ":" + world.getName());
        if (reported.add(key)) {
            String where = world == null ? "" : (" 位置 " + world.getName() + " " + x + "," + z);
            plugin.getLogger().log(Level.WARNING,
                    "无法提交区域调度任务（" + kind + "）" + where + "，本次跳过；后续同类问题只记录 DEBUG",
                    throwable);
            return;
        }
        plugin.getLogger().log(Level.FINE, "区域调度失败（" + kind + "）：" + throwable.getMessage());
    }
}
