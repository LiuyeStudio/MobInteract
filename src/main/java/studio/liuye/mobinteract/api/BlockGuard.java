package studio.liuye.mobinteract.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Mob;

/**
 * 保护区 / 区域权限的外部扩展点。
 *
 * <p>插件默认不绑定任何具体的保护区插件（例如 WorldGuard、GriefPrevention），
 * 而是通过该接口与 {@link MobBlockChangeEvent} 暴露统一的拒绝渠道：
 * 其他插件可以注册一个 {@code BlockGuard}（例如一个小的桥接插件），
 * 从而让怪物挖掘 / 放置也走保护区判定。</p>
 *
 * <p>实现必须线程安全，因为同一个实例会被不同区域线程调用；
 * 且只应做轻量的本地判断，不要访问其他区域的方块或实体。</p>
 */
public interface BlockGuard {

    /**
     * @param block 目标方块（调用时处于该方块所在区域的线程）
     * @param mob   发起操作的怪物
     * @return true 表示允许修改该方块
     */
    boolean canChange(Block block, Mob mob);
}
