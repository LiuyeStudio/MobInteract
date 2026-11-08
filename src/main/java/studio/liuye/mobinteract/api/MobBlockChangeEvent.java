package studio.liuye.mobinteract.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 怪物即将修改方块（挖掘完成 / 放置）之前触发的事件。
 *
 * <p>事件在<b>目标方块所在区域的线程</b>上同步触发，因此监听者可以直接读写该方块。
 * 取消事件即可阻止这次方块修改，插件随后会放弃本次行为。</p>
 */
public final class MobBlockChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** 方块修改的类型。 */
    public enum Action {
        /** 挖掉目标方块。 */
        BREAK,
        /** 在目标位置放置方块 / 倾倒流体 / 生成点燃的 TNT。 */
        PLACE
    }

    private final Mob mob;
    private final Block block;
    private final Action action;
    private boolean cancelled;

    public MobBlockChangeEvent(Mob mob, Block block, Action action) {
        this.mob = mob;
        this.block = block;
        this.action = action;
    }

    public Mob getMob() {
        return mob;
    }

    public Block getBlock() {
        return block;
    }

    public Action getAction() {
        return action;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
