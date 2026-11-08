package studio.liuye.mobinteract.placement;

/**
 * 已经选定的“垫高列”。
 *
 * <p>高处目标走“先垫高、再横向搭桥”：怪物会先把方块垫在自己脚下，沿着同一列往上爬到目标高度，
 * 爬到之后再朝目标铺一条直线桥。列一旦选定就固定下来（见 {@code MobProfile#buildLane()}），
 * 否则每几 tick 换一次落点，就会变成“到处乱放”。</p>
 *
 * @param x 这一列的方块 X
 * @param z 这一列的方块 Z
 */
public record BuildLane(int x, int z) {
}
