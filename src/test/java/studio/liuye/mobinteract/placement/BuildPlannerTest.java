package studio.liuye.mobinteract.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 搭路规划（{@link BuildPlanner}）的纯逻辑测试。
 *
 * <p>覆盖三种策略的取舍与各自的细节：</p>
 * <ul>
 *   <li>横向很远 → 搭楼梯（每一级同时前进一格、抬高一级）；</li>
 *   <li>高度差过大 / 横向太近 → 原地垫高（先找空列，整列不能有其它生物）；</li>
 *   <li>其余 → 垫高到目标高度后朝目标搭桥；</li>
 *   <li>前面已经有台阶、前方是墙、落点有同伴时不乱放。</li>
 * </ul>
 */
class BuildPlannerTest {

    private static final int GROUND_Y = 0;

    /** 测试默认值：横向 ≥4 格搭楼梯、≥8 格高度一律垫高、≤2 格一律垫高。 */
    private static final double STAIR_MIN_DISTANCE = 4.0D;
    private static final int PILLAR_FAR_HEIGHT = 8;
    private static final double PILLAR_NEAR_DISTANCE = 2.0D;

    // ------------------------------------------------------------------
    // 楼梯：横向很远
    // ------------------------------------------------------------------

    @Test
    void farTargetIsClimbedWithStairs() {
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 5, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind(), "横向远：搭楼梯");
        assertEquals(new GridPos(1, 1, 0), plan.step().pos(), "楼梯格放在身前与脚同高的那一格");
        assertNull(plan.lane(), "楼梯不需要垫高列");
    }

    @Test
    void stairsAreBuiltOnTheDominantAxisOnly() {
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 20);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(8, 4, 6)).build(), world);

        assertEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind());
        assertEquals(new GridPos(1, 1, 0), plan.step().pos(), "先沿水平差更大的 X 轴搭，不斜着放");
    }

    @Test
    void stairsHandOverToBridgeOnceTheHeightIsReached() {
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8); // 往东是沟壑

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 5, 0), new GridPos(10, 5, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, plan.step().kind(), "到同高以后改为铺桥面");
        assertEquals(new GridPos(1, 4, 0), plan.step().pos());
    }

    @Test
    void stairsStopWhenATwoBlockWallIsInTheWay() {
        // 前方两格高的墙：跳不上去，改用垫高绕到墙顶。
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8)
                .solid(1, 1, 0)
                .solid(1, 2, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 5, 0)).build(), world);

        assertNotEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind(), "墙前不硬搭楼梯");
        assertTrue(plan.step().isAction(), "改为垫高");
        assertNotNull(plan.lane());
    }

    @Test
    void stairsWalkOntoAnExistingOneBlockStep() {
        // 前方正好有一级现成的台阶：跳上去就行，不要再垫方块，也不要改成垫高。
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8).solid(1, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 5, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.WALK, plan.step().kind(), "已有台阶就直接走上去");
        assertEquals(new GridPos(1, 2, 0), plan.step().pos(), "走到台阶上方那一格");
    }

    @Test
    void stairsWaitWhenAnotherMobStandsOnTheStep() {
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8).entity(1, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 5, 0)).build(), world);

        assertNotEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind(), "不要把同伴挤进方块里");
        assertTrue(plan.step().isAction(), "改为垫高绕开（先走到旁边空列）");
        assertNotNull(plan.lane(), "垫高列要被记住");
    }

    @Test
    void stairsAreSkippedBeyondTheLengthLimit() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(40, 5, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "目标太远时不搭长楼梯");
    }

    // ------------------------------------------------------------------
    // 垫高：高度过大 / 横向过近
    // ------------------------------------------------------------------

    @Test
    void veryTallTargetIsPillaredInsteadOfStairs() {
        // 高度差 ≥ pillar-far-height：楼梯会一路朝前冲过头，改为垫高。
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 12, 0)).build(), world);

        assertNotEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind(), "高度过大不搭楼梯");
        assertNotNull(plan.lane(), "垫高列要被记住");
    }

    @Test
    void nearbyTargetIsPillaredInsteadOfStairs() {
        // 横向 ≤ pillar-near-distance：楼梯没有前进空间，直接垫高。
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(2, 5, 0)).build(), world);

        assertNotEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind(), "横向太近不搭楼梯");
        assertNotNull(plan.lane());
    }

    @Test
    void highTargetStartsWithPillarUpUnderItsOwnFeet() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        GridPos mob = new GridPos(0, 1, 0);
        GridPos target = new GridPos(0, 6, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(query(mob, target).build(), world);

        assertEquals(BuildPlanner.Kind.PILLAR_UP, plan.step().kind(), "高处目标应该先垫高");
        assertEquals(mob, plan.step().pos(), "垫高就是把自己脚下这一格补上");
        assertEquals(new BuildLane(0, 0), plan.lane(), "垫高列要固定在脚下这一列");
    }

    @Test
    void pillarColumnIsAbandonedWhenSomethingBlocksTheWayUp() {
        // 头顶一块石头（比如目标所在的平台）把脚下这一列挡死：必须换一列，而不是硬垫。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6).solid(0, 4, 0);
        GridPos mob = new GridPos(0, 1, 0);
        GridPos target = new GridPos(0, 6, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(query(mob, target).build(), world);

        assertEquals(BuildPlanner.Kind.RELOCATE, plan.step().kind(), "被挡死时先走到旁边空列");
        assertNotNull(plan.lane());
        assertNotEquals(new BuildLane(0, 0), plan.lane(), "不能再选被挡死的这一列");
        assertEquals(plan.lane().x(), plan.step().pos().x(), "移动目标就是选定的那一列");
        assertEquals(plan.lane().z(), plan.step().pos().z(), "移动目标就是选定的那一列");
        assertEquals(mob.y(), plan.step().pos().y(), "只在同一高度换列");
    }

    @Test
    void pillarColumnWithAnotherMobIsSkipped() {
        // 另一只生物挤在同一列里：垫上去会和同伴叠在一起，换一列。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6)
                .entity(0, 2, 0)
                .entity(0, 3, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.RELOCATE, plan.step().kind());
        assertNotNull(plan.lane());
        assertNotEquals(new BuildLane(0, 0), plan.lane(), "整列有其它生物就不能垫");
    }

    @Test
    void rejectedLaneIsNeverChosenAgain() {
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6);

        BuildPlanner.Query query = query(new GridPos(0, 1, 0), new GridPos(0, 6, 0))
                .laneRadius(1)
                .rejected(Set.of(new BuildLane(0, 0), new BuildLane(1, 0), new BuildLane(-1, 0),
                        new BuildLane(0, 1), new BuildLane(0, -1), new BuildLane(1, 1),
                        new BuildLane(1, -1), new BuildLane(-1, 1), new BuildLane(-1, -1)))
                .build();

        BuildPlanner.Plan plan = BuildPlanner.plan(query, world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "所有列都被放弃后不再垫高");
        assertNull(plan.lane());
    }

    @Test
    void chosenLaneIsReusedUntilTheMobStandsInIt() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        GridPos target = new GridPos(0, 6, 0);

        // 上一轮已经选好了第 2 列，但怪物还站在第 0 列：应该继续走过去。
        BuildPlanner.Plan move = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), target).lane(new BuildLane(2, 0)).build(), world);
        assertEquals(BuildPlanner.Kind.RELOCATE, move.step().kind());
        assertEquals(new GridPos(2, 1, 0), move.step().pos());
        assertEquals(new BuildLane(2, 0), move.lane(), "已经选好的列不能每轮换一个");

        // 走到位之后就原地垫高。
        BuildPlanner.Plan climb = BuildPlanner.plan(
                query(new GridPos(2, 1, 0), target).lane(new BuildLane(2, 0)).build(), world);
        assertEquals(BuildPlanner.Kind.PILLAR_UP, climb.step().kind());
        assertEquals(new GridPos(2, 1, 0), climb.step().pos());
    }

    @Test
    void pillarStopsWhenTheBudgetIsUsedUp() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        GridPos mob = new GridPos(0, 1, 0);
        GridPos target = new GridPos(0, 6, 0);

        BuildPlanner.Plan limited = BuildPlanner.plan(
                query(mob, target).remainingPillarSteps(2).build(), world);
        assertEquals(BuildPlanner.Kind.PILLAR_UP, limited.step().kind(), "还有预算就继续垫");

        BuildPlanner.Plan exhausted = BuildPlanner.plan(
                query(mob, target).remainingPillarSteps(0).build(), world);
        assertEquals(BuildPlanner.Kind.NONE, exhausted.step().kind(), "预算用尽就不再垫高");
    }

    @Test
    void waitsOnGroundBeforeBuilding() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        BuildPlanner.Query query = query(new GridPos(0, 4, 0), new GridPos(0, 9, 0))
                .onGround(false)
                .build();

        BuildPlanner.Plan plan = BuildPlanner.plan(query, world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "腾空时不垫方块");
    }

    @Test
    void towerTopIsReachedByPillarThenWalk() {
        // 玩家站在 4 格高的柱子上，怪物在柱子旁边：先走到离目标最近的空列垫高，
        // 爬到柱顶高度后旁边就是柱顶，直接走过去。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6);
        for (int y = 1; y <= 4; y++) {
            world.solid(0, y, 0); // 柱子
        }
        GridPos target = new GridPos(0, 5, 0);

        BuildPlanner.Plan approach = BuildPlanner.plan(query(new GridPos(2, 1, 0), target).build(), world);
        assertEquals(BuildPlanner.Kind.RELOCATE, approach.step().kind(), "先走到目标旁边的空列");
        assertEquals(new BuildLane(1, 0), approach.lane());
        assertEquals(new GridPos(1, 1, 0), approach.step().pos());

        BuildPlanner.Plan climb = BuildPlanner.plan(
                query(new GridPos(1, 1, 0), target).lane(new BuildLane(1, 0)).build(), world);
        assertEquals(BuildPlanner.Kind.PILLAR_UP, climb.step().kind(), "选好列后就地垫高");
        assertEquals(new GridPos(1, 1, 0), climb.step().pos());

        BuildPlanner.Plan top = BuildPlanner.plan(
                query(new GridPos(1, 5, 0), target).lane(new BuildLane(1, 0)).build(), world);
        assertEquals(BuildPlanner.Kind.WALK, top.step().kind(), "柱顶就在旁边：走上去即可");
        assertEquals(new GridPos(0, 5, 0), top.step().pos(), "走到目标所在那一格");
    }

    @Test
    void towerOnTheOtherSideIsBridgedAfterClimbing() {
        // 玩家站在柱子上，怪物爬到同高后与玩家还隔着一格空档：这时才需要横向搭桥。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6);
        for (int y = 1; y <= 4; y++) {
            world.solid(0, y, 0);
        }

        BuildPlanner.Plan bridge = BuildPlanner.plan(
                query(new GridPos(2, 5, 0), new GridPos(0, 5, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, bridge.step().kind(), "爬到高度后再横向搭桥");
        assertEquals(new GridPos(1, 4, 0), bridge.step().pos(), "桥面铺在前方脚下");
        assertNull(bridge.lane(), "开始搭桥就不再需要垫高列");
    }

    // ------------------------------------------------------------------
    // 搭桥：同一高度的沟壑
    // ------------------------------------------------------------------

    @Test
    void bridgeRunsTowardTheTargetOnly() {
        // 东边是沟壑、西边是实地：桥只朝目标方向铺。
        FakeWorld east = new FakeWorld().ground(-8, 0, -8, 8);
        BuildPlanner.Plan toEast = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), east);
        assertEquals(BuildPlanner.Kind.BRIDGE, toEast.step().kind());
        assertEquals(new GridPos(1, 0, 0), toEast.step().pos(), "只朝目标方向铺桥面");

        // 西边是沟壑、东边是实地：同样只朝目标方向铺。
        FakeWorld west = new FakeWorld().ground(0, 8, -8, 8);
        BuildPlanner.Plan toWest = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(-4, 1, 0)).build(), west);
        assertEquals(new GridPos(-1, 0, 0), toWest.step().pos(), "目标在西边就往西铺");
    }

    @Test
    void bridgeUsesTheDominantAxisSoNoDiagonalZigzag() {
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(3, 1, 2)).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, plan.step().kind());
        assertEquals(1, plan.step().pos().x(), "先消掉较大的水平差（X 方向）");
        assertEquals(0, plan.step().pos().z(), "斜向不铺，避免锯齿");
    }

    @Test
    void oneBlockRiseIsLeftToVanillaJumping() {
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(3, 2, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, plan.step().kind(), "只高 1 格：搭桥后靠原版跳跃上去");
        assertNull(plan.lane(), "不值得为 1 格专门垫高");
    }

    @Test
    void bridgeStopsWhenTheWayIsBlocked() {
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8)
                .solid(1, 1, 0)
                .solid(1, 2, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "前方是墙就不硬搭");
    }

    @Test
    void bridgeWalksWhenTheGroundAheadIsAlreadyWalkable() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        // 必须明确“走这一格”：原版寻路常常因为对面到不了而不给移动指令，
        // 怪物铺完一格就干站着（表现为搭桥时莫名其妙停下）。
        assertEquals(BuildPlanner.Kind.WALK, plan.step().kind(), "脚下已经有地：直接走过去");
        assertEquals(new GridPos(1, 1, 0), plan.step().pos(), "走到前方那一格");
    }

    @Test
    void bridgeRefusesWhenThereIsNoHeadroomToWalkThrough() {
        // 前方只有一格高（脚能过、头过不去）：铺了也走不了，干脆不放。
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8).solid(1, 2, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "前方高度不够就不搭桥");
    }

    @Test
    void bridgeWaitsWhenAnotherMobStandsInTheWay() {
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8).entity(1, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "不要往同伴身上铺桥面");
    }

    @Test
    void lastBlockIsLaidUnderTheChaseTarget() {
        // 目标就站在正前方（身体占着前面那一格），下面还是空的：
        // 桥面必须补上，否则就是“只差最后一格不搭了”。
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8);
        world.target(1, 1, 0).target(1, 2, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(1, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, plan.step().kind(), "目标本人不该被当成占位生物");
        assertEquals(new GridPos(1, 0, 0), plan.step().pos(), "补上目标脚下那一格");
    }

    @Test
    void stairsAreStillBlockedByTheTargetsBody() {
        // 楼梯是“把方块放进前面那一格”，目标站着就不能放（会把人挤起来）。
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8);
        world.target(1, 1, 0).target(1, 2, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 5, 0)).build(), world);

        assertNotEquals(BuildPlanner.Kind.STAIR_UP, plan.step().kind(), "不能把楼梯放进目标身体里");
    }

    @Test
    void walksUpAOneBlockStepInsteadOfStopping() {
        // 前方正好高出一格：原版能跳上去，别在这里停下。
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8).solid(1, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.WALK, plan.step().kind());
        assertEquals(new GridPos(1, 2, 0), plan.step().pos(), "走到台阶上方那一格");
    }

    @Test
    void noBridgeWhenAlreadyInTheTargetColumn() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(2, 1, 3), new GridPos(2, 2, 3)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind());
    }

    @Test
    void bridgeStopsBeyondTheLengthLimit() {
        FakeWorld world = new FakeWorld().ground(-8, 0, -8, 8);

        BuildPlanner.Plan within = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);
        assertEquals(BuildPlanner.Kind.BRIDGE, within.step().kind());

        BuildPlanner.Plan tooFar = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(40, 1, 0)).build(), world);
        assertEquals(BuildPlanner.Kind.NONE, tooFar.step().kind(), "太远就不铺天桥");
    }

    // ------------------------------------------------------------------
    // 下潜 / 其它边界
    // ------------------------------------------------------------------

    @Test
    void descendsTowardALowerTarget() {
        FakeWorld world = new FakeWorld(); // 全是空气：悬崖外
        GridPos mob = new GridPos(0, 5, 0);
        GridPos target = new GridPos(0, 2, 4);

        BuildPlanner.Plan down = BuildPlanner.plan(query(mob, target).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, down.step().kind(), "目标明显更低：往下搭");
        assertEquals(new GridPos(0, 3, 1), down.step().pos(),
                "落点是正前方、低两格（站上去正好 1 格落差）");
    }

    @Test
    void doesNotPaveOverASmallDip() {
        // 前方是一个 2 格深的小坑：原版自己会跳下去，不该铺路。
        FakeWorld world = new FakeWorld()
                .floorAt(0, -8, 0, -8, 8)
                .floorAt(-2, 1, 4, -8, 8)
                .floorAt(0, 5, 8, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(6, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.WALK, plan.step().kind(), "小坑直接走下去，不铺路");
    }

    @Test
    void bridgesOverADeepGap() {
        // 4 格以上才算沟：这时才铺桥面。
        FakeWorld world = new FakeWorld()
                .floorAt(0, -8, 0, -8, 8)
                .floorAt(-6, 1, 4, -8, 8)
                .floorAt(0, 5, 8, -8, 8);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(6, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, plan.step().kind(), "深沟才铺桥");
        assertEquals(new GridPos(1, 0, 0), plan.step().pos(), "同高度铺在前方脚下");
    }

    @Test
    void walksOffAOneBlockDropInsteadOfBuildingLevel() {
        // 前下方就是实地（只差 1 格落差）：直接走下去，别在上面铺平桥。
        FakeWorld world = new FakeWorld().solid(0, 3, 1);
        GridPos mob = new GridPos(0, 5, 0);
        GridPos target = new GridPos(0, 2, 4);

        BuildPlanner.Plan plan = BuildPlanner.plan(query(mob, target).build(), world);

        assertEquals(BuildPlanner.Kind.WALK, plan.step().kind());
        assertEquals(new GridPos(0, 5, 1), plan.step().pos(), "走到前方那一格，靠原版自己落下去");
    }

    @Test
    void smallDropStillBridgesLevel() {
        // 落差不到 vertical-drop（默认 1.5）：按同高度铺桥处理。
        FakeWorld world = new FakeWorld();
        GridPos mob = new GridPos(0, 5, 0);
        GridPos target = new GridPos(0, 4, 4);

        BuildPlanner.Plan plan = BuildPlanner.plan(query(mob, target).build(), world);

        assertEquals(BuildPlanner.Kind.BRIDGE, plan.step().kind());
        assertEquals(new GridPos(0, 4, 1), plan.step().pos(), "同高度铺桥面");
    }

    @Test
    void doesNotDescendIntoWater() {
        FakeWorld world = new FakeWorld();
        world.liquid(0, 3, 1).liquid(0, 4, 1);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 5, 0), new GridPos(0, 2, 4)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "前下方是水就不往下搭");
        assertTrue(plan.step().reason().contains("水"), plan.step().reason());
    }

    @Test
    void noPlacementWhenEveryColumnIsCapped() {
        // 只有这一列可选，而头顶被挡死：宁可不放，也不要在地上乱垫方块。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6).solid(0, 3, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).laneRadius(0).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind());
        assertNull(plan.lane());
    }

    @Test
    void liquidColumnIsNotUsedForPillaring() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8).liquid(0, 5, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).build(), world);

        assertTrue(plan.step().isAction(), "应当换一列继续垫高");
        assertNotEquals(new BuildLane(0, 0), plan.lane(), "不能往水里垫方块");
    }

    @Test
    void wetMobFightsInsteadOfSwimmingAshoreWhenTheTargetIsRightThere() {
        // 目标就在身边（水里贴着打）：不该先游上岸。
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8).liquid(0, 1, 0).liquid(0, 2, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 2, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "近战优先，不往岸上跑");
        assertTrue(plan.step().reason().contains("近战"), plan.step().reason());
    }

    @Test
    void wetMobChasesAWetTargetInsteadOfSwimmingAshore() {
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        world.liquid(0, 1, 0);
        world.liquid(5, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(5, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "目标也在水里：直接游过去");
        assertTrue(plan.step().reason().contains("游过去"), plan.step().reason());
    }

    @Test
    void stairAndPillarCanBeDisabledIndependently() {
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8);
        GridPos mob = new GridPos(0, 1, 0);
        GridPos target = new GridPos(10, 5, 0);

        BuildPlanner.Plan noStairs = BuildPlanner.plan(query(mob, target).stairEnabled(false).build(), world);
        assertEquals(BuildPlanner.Kind.RELOCATE, noStairs.step().kind(), "关掉楼梯就改为垫高（先换列）");

        BuildPlanner.Plan noPillar = BuildPlanner.plan(
                query(mob, target).stairEnabled(true).pillarEnabled(false).build(), world);
        assertEquals(BuildPlanner.Kind.STAIR_UP, noPillar.step().kind(), "关掉垫高仍然可以搭楼梯");
    }

    @Test
    void minesTheCeilingInsteadOfRelocatingWhenTheMobHasAPickaxe() {
        // 头顶那一格压着石头，挖掉它这一列就能继续往上垫：有镐子就先挖，别换列。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6).solid(0, 3, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).canDigUp(true).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "交给挖掘行为去挖头顶");
        assertTrue(plan.step().reason().contains("挖掉"), plan.step().reason());
        assertNull(plan.lane());
    }

    @Test
    void relocatesWhenTheMobHasNoPickaxeForTheCeiling() {
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6).solid(0, 3, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).canDigUp(false).build(), world);

        assertEquals(BuildPlanner.Kind.RELOCATE, plan.step().kind(), "没镐子就换一列垫高");
        assertNotNull(plan.lane());
    }

    @Test
    void relocatesWhenTheCeilingIsNotTheOnlyBlocker() {
        // 头顶挡住了，但上面还有别的挡路方块：挖掉头顶也没用，还是换列。
        FakeWorld world = new FakeWorld().ground(-6, 6, -6, 6).solid(0, 3, 0).solid(0, 5, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).canDigUp(true).build(), world);

        assertEquals(BuildPlanner.Kind.RELOCATE, plan.step().kind());
    }

    // ------------------------------------------------------------------
    // 液体：不泡在水里搭、不往水里搭
    // ------------------------------------------------------------------

    @Test
    void wetMobWalksToTheShoreInsteadOfBuilding() {
        // 怪物泡在水里、目标在正上方：先上岸，别在水里垫方块。
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        world.liquid(0, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.WALK, plan.step().kind(), "在水里先上岸，不放方块");
        assertNotEquals(0, plan.step().pos().x() + plan.step().pos().z(), "上岸目标不能还是自己那一格");
        assertNull(plan.lane(), "还没选垫高列");
    }

    @Test
    void wetMobStaysPutWhenThereIsNoShore() {        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.liquid(x, 1, z);
            }
        }

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(0, 6, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind(), "没有岸就不放方块（交给原版游过去）");
        assertTrue(plan.step().reason().contains("水"), plan.step().reason());
    }

    @Test
    void bridgeIsNotBuiltOverWater() {
        // 前方脚下是水：不铺桥（铺了也立不住），交给原版绕行。
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        world.liquid(1, 0, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind());
        assertTrue(plan.step().reason().contains("水"), plan.step().reason());
    }

    @Test
    void bridgeIsNotBuiltIntoWater() {
        // 前方那一格本身就是水（脚会踩进水里）：不搭。
        FakeWorld world = new FakeWorld().ground(-8, 8, -8, 8);
        world.liquid(1, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(4, 1, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind());
        assertTrue(plan.step().reason().contains("水"), plan.step().reason());
    }

    @Test
    void stairsAreNotBuiltIntoWater() {
        FakeWorld world = new FakeWorld().ground(-8, 20, -8, 8);
        world.liquid(1, 1, 0);

        BuildPlanner.Plan plan = BuildPlanner.plan(
                query(new GridPos(0, 1, 0), new GridPos(10, 5, 0)).build(), world);

        assertEquals(BuildPlanner.Kind.NONE, plan.step().kind());
        assertTrue(plan.step().reason().contains("水"), plan.step().reason());
    }

    // ------------------------------------------------------------------

    private static QueryBuilder query(GridPos mob, GridPos target) {
        return new QueryBuilder(mob, target);
    }

    /** 构造 {@link BuildPlanner.Query} 的小工具，默认值就是“全部开启”。 */
    private static final class QueryBuilder {

        private final GridPos mob;
        private final GridPos target;
        private boolean onGround = true;
        private boolean stairEnabled = true;
        private double stairMinDistance = STAIR_MIN_DISTANCE;
        private boolean pillarEnabled = true;
        private int pillarFarHeight = PILLAR_FAR_HEIGHT;
        private double pillarNearDistance = PILLAR_NEAR_DISTANCE;
        private int remainingPillarSteps = -1;
        private int laneRadius = 3;
        private boolean bridgeEnabled = true;
        private int bridgeMaxLength = 24;
        private double verticalDrop = 1.5D;
        private BuildLane lane;
        private Set<BuildLane> rejectedLanes;
        private boolean canDigUp;

        QueryBuilder(GridPos mob, GridPos target) {
            this.mob = mob;
            this.target = target;
        }

        QueryBuilder onGround(boolean value) {
            this.onGround = value;
            return this;
        }

        QueryBuilder stairEnabled(boolean value) {
            this.stairEnabled = value;
            return this;
        }

        QueryBuilder pillarEnabled(boolean value) {
            this.pillarEnabled = value;
            return this;
        }

        QueryBuilder pillarFarHeight(int value) {
            this.pillarFarHeight = value;
            return this;
        }

        QueryBuilder pillarNearDistance(double value) {
            this.pillarNearDistance = value;
            return this;
        }

        QueryBuilder remainingPillarSteps(int value) {
            this.remainingPillarSteps = value;
            return this;
        }

        QueryBuilder laneRadius(int value) {
            this.laneRadius = value;
            return this;
        }

        QueryBuilder lane(BuildLane value) {
            this.lane = value;
            return this;
        }

        QueryBuilder rejected(Set<BuildLane> value) {
            this.rejectedLanes = value;
            return this;
        }

        QueryBuilder canDigUp(boolean value) {
            this.canDigUp = value;
            return this;
        }

        BuildPlanner.Query build() {
            return new BuildPlanner.Query(mob, target, onGround, stairEnabled, stairMinDistance,
                    pillarEnabled, pillarFarHeight, pillarNearDistance,
                    remainingPillarSteps, laneRadius, bridgeEnabled, bridgeMaxLength, verticalDrop,
                    lane, rejectedLanes, canDigUp);
        }
    }

    /** 假世界：默认全是空气，按需标记实心 / 液体 / 其它生物 / 追击目标占位。 */
    private static final class FakeWorld implements BuildWorld {

        private final Set<GridPos> solid = new HashSet<>();
        private final Set<GridPos> liquids = new HashSet<>();
        private final Set<GridPos> entities = new HashSet<>();
        /** 追击目标占着的格子：算“占位”，但不算“别的生物”。 */
        private final Set<GridPos> targetCells = new HashSet<>();

        FakeWorld ground(int fromX, int toX, int fromZ, int toZ) {
            return floorAt(GROUND_Y, fromX, toX, fromZ, toZ);
        }

        /** 指定高度的一层地面。 */
        FakeWorld floorAt(int y, int fromX, int toX, int fromZ, int toZ) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    solid.add(new GridPos(x, y, z));
                }
            }
            return this;
        }

        FakeWorld solid(int x, int y, int z) {
            solid.add(new GridPos(x, y, z));
            return this;
        }

        FakeWorld liquid(int x, int y, int z) {
            liquids.add(new GridPos(x, y, z));
            return this;
        }

        FakeWorld entity(int x, int y, int z) {
            entities.add(new GridPos(x, y, z));
            return this;
        }

        /** 追击目标的身体格（不含自己）。 */
        FakeWorld target(int x, int y, int z) {
            targetCells.add(new GridPos(x, y, z));
            return this;
        }

        @Override
        public boolean isLoaded(int x, int y, int z) {
            return y >= -64 && y < 320;
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            return !solid.contains(new GridPos(x, y, z));
        }

        @Override
        public boolean isReplaceable(int x, int y, int z) {
            // 假世界里“能穿过”就等于“能被方块替换”（空气 / 草 / 流体）。
            return isPassable(x, y, z);
        }

        @Override
        public boolean isLiquid(int x, int y, int z) {
            return liquids.contains(new GridPos(x, y, z));
        }

        @Override
        public boolean hasEntity(int x, int yFrom, int yTo, int z) {
            return contains(entities, x, yFrom, yTo, z);
        }

        @Override
        public boolean hasAnyEntity(int x, int yFrom, int yTo, int z) {
            return contains(entities, x, yFrom, yTo, z) || contains(targetCells, x, yFrom, yTo, z);
        }

        private static boolean contains(Set<GridPos> cells, int x, int yFrom, int yTo, int z) {
            for (int y = yFrom; y <= yTo; y++) {
                if (cells.contains(new GridPos(x, y, z))) {
                    return true;
                }
            }
            return false;
        }
    }
}
