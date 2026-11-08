package studio.liuye.mobinteract.placement;

/**
 * 纯逻辑层使用的方块坐标（世界坐标三元组）。
 *
 * <p>搭路规划不需要 Bukkit 的 {@link org.bukkit.block.Block}：坐标只用这个不可变值对象表示，
 * 方块与实体的读取统一走 {@link BuildWorld}。这样 {@link BuildPlanner} 可以在没有服务端的
 * 情况下用假世界做单元测试，也不会把 {@code World} / {@code Block} 带进纯逻辑。</p>
 *
 * @param x 方块 X
 * @param y 方块 Y
 * @param z 方块 Z
 */
public record GridPos(int x, int y, int z) {

    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }
}
