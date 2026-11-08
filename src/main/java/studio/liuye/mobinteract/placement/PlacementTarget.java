package studio.liuye.mobinteract.placement;

import studio.liuye.mobinteract.util.BlockPos;

/**
 * 一次放置的目标：放在哪、属于哪种放置方式。
 *
 * <p>“竖直垫高”（{@link Kind#PILLAR_UP}）与其它方式的区别在于：它要把方块垫在怪物自己脚下，
 * 所以执行前需要先把怪物抬高一格，否则方块会生成在它身体里。</p>
 *
 * @param pos  目标方块坐标
 * @param kind 放置方式
 */
public record PlacementTarget(BlockPos pos, Kind kind) {

    public enum Kind {
        /** 朝目标搭一级楼梯：方块放在前方与脚同高的那一格，跳上去同时前进一格、抬高一级。 */
        STAIR_UP,
        /** 在脚下垫方块竖直上升（先垫高到目标高度，再横向搭桥）。 */
        PILLAR_UP,
        /** 朝目标横向搭桥：放在前方脚下，铺成桥面。 */
        BRIDGE,
        /** 选定的垫高列不在脚下：先走过去，这一轮不放置。 */
        RELOCATE,
        /** 路已经通了（前方有地面 / 有现成台阶）：让怪物自己走上去，这一轮不放置。 */
        WALK,
        /** 直接放在目标（玩家）脚下（TNT / 水 / 熔岩桶等攻击性用法）。 */
        UNDER_TARGET
    }

    public static PlacementTarget stairUp(BlockPos pos) {
        return new PlacementTarget(pos, Kind.STAIR_UP);
    }

    public static PlacementTarget pillarUp(BlockPos pos) {
        return new PlacementTarget(pos, Kind.PILLAR_UP);
    }

    public static PlacementTarget bridge(BlockPos pos) {
        return new PlacementTarget(pos, Kind.BRIDGE);
    }

    public static PlacementTarget relocate(BlockPos pos) {
        return new PlacementTarget(pos, Kind.RELOCATE);
    }

    public static PlacementTarget walk(BlockPos pos) {
        return new PlacementTarget(pos, Kind.WALK);
    }

    public static PlacementTarget underTarget(BlockPos pos) {
        return new PlacementTarget(pos, Kind.UNDER_TARGET);
    }

    public boolean isPillarUp() {
        return kind == Kind.PILLAR_UP;
    }

    /** 是否是“搭一级楼梯”（与竖直垫高一样是连续爬升，但每一级还会前进一格）。 */
    public boolean isStairUp() {
        return kind == Kind.STAIR_UP;
    }

    /** 是否只是“走到垫高列”，不需要真正放置方块。 */
    public boolean isRelocate() {
        return kind == Kind.RELOCATE;
    }

    /** 是否只是“往前走一格”（前方已经有地面 / 台阶），不需要真正放置方块。 */
    public boolean isWalk() {
        return kind == Kind.WALK;
    }

    /** 是否只是移动指令（不消耗方块）。 */
    public boolean isMoveOnly() {
        return kind == Kind.RELOCATE || kind == Kind.WALK;
    }
}
