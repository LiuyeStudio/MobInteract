package studio.liuye.mobinteract.placement;

/**
 * 搭路规划只读的“世界视图”。
 *
 * <p>{@link BuildPlanner} 只通过这个接口读方块与实体，因此不需要 Bukkit 就能单元测试；
 * 运行期由 {@code BukkitBuildWorld} 把 Bukkit 世界适配成这个接口。</p>
 *
 * <p>约定：<b>先 {@link #isLoaded} 再读其它属性</b>。坐标越界 / 区块未加载 / 区域不可用时
 * {@link #isLoaded} 返回 {@code false}，实现不保证其它方法对这类坐标有效。</p>
 */
public interface BuildWorld {

    /** 该格是否可读（世界高度范围内、区块已加载、且当前线程拥有它所在的区域）。 */
    boolean isLoaded(int x, int y, int z);

    /** 该格是否可以通过（空气、草、告示牌等可穿过）。 */
    boolean isPassable(int x, int y, int z);

    /** 该格是否可以被方块替换（空气、草、雪层、流体等）。 */
    boolean isReplaceable(int x, int y, int z);

    /** 该格是否是液体。 */
    boolean isLiquid(int x, int y, int z);

    /**
     * 垂直区间 {@code [yFrom, yTo]} 里是否有<b>其它</b>生物占着。
     *
     * <p>“其它”指：不含发起搭路的怪物自己，也<b>不含它的追击目标</b>。
     * 目标本来就要被接近，最后几格往往正好落在目标脚下 / 身边，把它算成占位会让怪物
     * “只差最后一格就不搭了”。</p>
     */
    boolean hasEntity(int x, int yFrom, int yTo, int z);

    /**
     * 垂直区间里是否有<b>任何</b>生物（不含怪物自己，<b>含</b>追击目标）。
     *
     * <p>用于“方块要放进这一格”的场合：把方块放进任何生物身体里都会挤压 / 窒息，
     * 目标也不行。</p>
     */
    boolean hasAnyEntity(int x, int yFrom, int yTo, int z);

    /** 单格是否有其它生物占着（不含自己与追击目标）。 */
    default boolean hasEntityAt(int x, int y, int z) {
        return hasEntity(x, y, y, z);
    }

    /** 单格是否有任何生物（不含自己，含追击目标）。 */
    default boolean hasAnyEntityAt(int x, int y, int z) {
        return hasAnyEntity(x, y, y, z);
    }
}
