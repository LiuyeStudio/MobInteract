package studio.liuye.mobinteract.safety;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import studio.liuye.mobinteract.api.BlockGuard;

/**
 * 保护区判定的聚合器。
 *
 * <p>插件自身不绑定任何保护区插件；外部通过 {@link #register(BlockGuard)} 注册判定，
 * 任何一个判定拒绝即视为受保护。没有注册任何判定时全部放行。</p>
 */
public final class ProtectionService {

    private final List<BlockGuard> guards = new CopyOnWriteArrayList<>();
    /** BlockLocker 兼容层（未安装时为 {@code null}）。 */
    private volatile BlockLockerSupport blockLocker;
    /** 本插件实例（BlockLocker 兼容层打日志用）。 */
    private volatile org.bukkit.plugin.Plugin plugin;

    /**
     * 尝试接入 BlockLocker（软依赖，未安装时什么也不做）。
     *
     * @param plugin 本插件（用于日志）
     */
    public void detectBlockLocker(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
        this.blockLocker = BlockLockerSupport.resolve(plugin);
    }

    /** 是否已接入 BlockLocker。 */
    public boolean hasBlockLocker() {
        return blockLocker != null;
    }

    public void register(BlockGuard guard) {
        if (guard != null) {
            guards.add(guard);
        }
    }

    public void unregister(BlockGuard guard) {
        guards.remove(guard);
    }

    public boolean canChange(Block block, Mob mob) {
        // BlockLocker：被它保护的方块（箱子 / 门 / 活板门……）一律不挖、不炸。
        BlockLockerSupport locker = blockLocker;
        if (locker != null && block != null && locker.isProtected(plugin, block)) {
            return false;
        }
        for (BlockGuard guard : guards) {
            try {
                if (!guard.canChange(block, mob)) {
                    return false;
                }
            } catch (Throwable throwable) {
                // 外部判定出错时不阻塞怪物行为，但要让服主知道。
                org.bukkit.Bukkit.getLogger().warning(
                        "BlockGuard " + guard.getClass().getName() + " 判定出错，本次按放行处理："
                                + throwable.getMessage());
            }
        }
        return true;
    }

    public int size() {
        return guards.size();
    }
}
