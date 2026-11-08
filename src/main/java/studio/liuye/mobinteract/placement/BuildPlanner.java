package studio.liuye.mobinteract.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 搭路（放置）规划的核心逻辑：按目标的高低远近在“搭楼梯 / 垫高 / 横桥”之间选，
 * 不做四处乱放。
 *
 * <p>策略：</p>
 * <ol>
 *   <li><b>横向距离很远</b>（≥ {@code stair-min-distance}，且高度没到 {@code pillar-far-height}）：
 *       朝目标<b>搭楼梯</b>——每一级同时前进一格、抬高一级，比“先竖着垫再横着铺”省方块。</li>
 *   <li><b>高度差过大</b>（≥ {@code pillar-far-height}）或<b>横向距离过近</b>
 *       （≤ {@code pillar-near-distance}）：楼梯会一路朝前冲过头，改为<b>原地垫高</b>。</li>
 *   <li>其余情况：<b>先垫高到目标高度，再横向搭桥</b>。垫高列要求整列没有其它生物、
 *       上下都有净空、头顶不被挡死；列一旦选定就固定下来，不会每几 tick 换一个落点。</li>
 *   <li>台阶已经有了、前方脚下本来就有实地、前方是墙且爬不上去时<b>不放置</b>：
 *       交给原版寻路或挖掘行为。</li>
 * </ol>
 *
 * <p>本类只读 {@link BuildWorld}，不做任何修改；调用方必须处于怪物所属区域的线程。
 * 纯逻辑、无 Bukkit 依赖，可直接单元测试（见 {@code BuildPlannerTest}）。</p>
 */
public final class BuildPlanner {

    /** 目标至少高出这么多格才值得动手往上搭；只高 1 格交给原版跳跃 / 台阶。 */
    private static final int MIN_PILLAR_RISE = 2;

    /** 目标水平方向就在身边（小于 1 格）时交给原版近战。 */
    private static final double MELEE_RANGE = 1.0D;

    /** 候选垫高列的“多走一格”惩罚：优先离目标近（桥更短）的列，但也不愿走太远。 */
    private static final double WALK_PENALTY = 0.5D;

    /** 每次挑选垫高列时最多实体查询几列（实体查询比读方块贵得多）。 */
    private static final int MAX_ENTITY_CHECKS = 4;

    /** 每次挑选垫高列时最多完整校验几列（防止所有列都被挡死时反复扫描）。 */
    private static final int MAX_COLUMN_CHECKS = 16;

    /** 找“上岸”落脚点的搜索半径（格）。只有泡在水里时才会扫。 */
    private static final int SHORE_RADIUS = 3;

    /**
     * 前方落差小于等于这么多格时，交给原版自己走下去，不铺路。
     *
     * <p>原版怪物能吃下 3 格落差，所以土坡 / 小台阶都该直接走；只有更深的沟才需要搭。</p>
     */
    private static final int MAX_SAFE_DROP = 3;

    /** 一次规划的动作类别。 */
    public enum Kind {
        /** 朝目标搭一级楼梯：方块放在前方与脚同高的那一格，怪物跳上去同时前进一格、抬高一级。 */
        STAIR_UP,
        /** 把方块垫在自己脚下（执行器会先把怪物抬高一格）。 */
        PILLAR_UP,
        /** 朝目标横向前进一格（放在前方脚下，铺成桥面）。 */
        BRIDGE,
        /** 选定的垫高列不在脚下：先走过去，到位后再垫高。 */
        RELOCATE,
        /** 路已经通了（前方有地面 / 有现成台阶）：让怪物自己走上去，不放置。 */
        WALK,
        /** 这一轮不需要 / 不能放置。 */
        NONE
    }

    /**
     * 一步规划结果。
     *
     * @param kind   动作类别
     * @param pos    动作坐标（{@link Kind#NONE} 时为 {@code null}）
     * @param reason 人话说明，便于诊断“为什么没动”
     */
    public record Step(Kind kind, GridPos pos, String reason) {

        static Step none(String reason) {
            return new Step(Kind.NONE, null, reason);
        }

        static Step stairUp(GridPos pos) {
            return new Step(Kind.STAIR_UP, pos, "楼梯：在身前垫一级，同时前进一格、抬高一级");
        }

        static Step pillarUp(GridPos pos) {
            return new Step(Kind.PILLAR_UP, pos, "垫高：抬高一格后把方块垫在脚下");
        }

        static Step bridge(GridPos pos) {
            return new Step(Kind.BRIDGE, pos, "搭桥：朝目标铺一格");
        }

        static Step relocate(GridPos pos) {
            return new Step(Kind.RELOCATE, pos, "走到没有阻挡的空列再垫高");
        }

        static Step walk(GridPos pos) {
            return new Step(Kind.WALK, pos, "路已经通了：让怪物自己走上去");
        }

        /** 是否是一个需要执行的动作（楼梯 / 垫高 / 搭桥 / 走过去）。 */
        public boolean isAction() {
            return kind != Kind.NONE;
        }
    }

    /**
     * 一次规划的输入。
     *
     * @param mobFeet              怪物脚所在方块
     * @param targetFeet           目标（玩家等）脚所在方块
     * @param onGround             怪物是否站在地面上（腾空时先不往上搭）
     * @param stairEnabled         是否允许搭楼梯
     * @param stairMinDistance     横向距离 ≥ 这个值时优先搭楼梯
     * @param pillarEnabled        是否允许垫高
     * @param pillarFarHeight      高度差 ≥ 这个值时一律垫高（不搭楼梯）
     * @param pillarNearDistance   横向距离 ≤ 这个值时一律垫高（楼梯会冲过头）
     * @param remainingPillarSteps 本次追击还能垫高多少格（&lt; 0 表示不限）
     * @param laneRadius           挑选垫高列的水平搜索半径（格）
     * @param bridgeEnabled        是否允许横向搭桥
     * @param bridgeMaxLength      离目标超过这么多格就不再搭桥（&lt;= 0 表示不限）
     * @param verticalDrop         判定“目标明显更低”的高度差（会改为往下搭一级）
     * @param lane                 上次选定的垫高列，可为 {@code null}
     * @param rejectedLanes        走不过去 / 放不下的列（本次追击内不再尝试），可为 {@code null}
     * @param canDigUp             头顶（脚上方第 2 格）那块方块能不能挖掉（有镐子且可挖）；
     *                             {@code true} 时“头顶挡死”优先让挖掘行为处理，而不是换列
     */
    public record Query(
            GridPos mobFeet,
            GridPos targetFeet,
            boolean onGround,
            boolean stairEnabled,
            double stairMinDistance,
            boolean pillarEnabled,
            int pillarFarHeight,
            double pillarNearDistance,
            int remainingPillarSteps,
            int laneRadius,
            boolean bridgeEnabled,
            int bridgeMaxLength,
            double verticalDrop,
            BuildLane lane,
            Set<BuildLane> rejectedLanes,
            boolean canDigUp) {

        boolean isRejected(BuildLane lane) {
            return lane != null && rejectedLanes != null && rejectedLanes.contains(lane);
        }
    }

    /**
     * 规划结果。
     *
     * @param step 下一步动作
     * @param lane 需要记住的垫高列；{@code null} 表示清空（已经爬到目标高度 / 改为搭桥）
     */
    public record Plan(Step step, BuildLane lane) {

        static Plan of(Step step, BuildLane lane) {
            return new Plan(step, lane);
        }

        static Plan none(String reason, BuildLane lane) {
            return new Plan(Step.none(reason), lane);
        }
    }

    /** 楼梯这一步的补充结论。 */
    private enum StairFallback {
        /** 就这样（放置或干脆不放置）。 */
        NONE,
        /** 这一级过不去，改用垫高绕过。 */
        PILLAR
    }

    /** 楼梯一步的结果。 */
    private record StairStep(Step step, StairFallback fallback) {

        static StairStep place(GridPos pos) {
            return new StairStep(Step.stairUp(pos), StairFallback.NONE);
        }

        static StairStep walk(GridPos pos) {
            return new StairStep(Step.walk(pos), StairFallback.NONE);
        }

        static StairStep stop(String reason) {
            return new StairStep(Step.none(reason), StairFallback.NONE);
        }

        static StairStep pillar(String reason) {
            return new StairStep(Step.none(reason), StairFallback.PILLAR);
        }
    }

    private BuildPlanner() {
    }

    /**
     * 怪物是不是“泡在水里”：脚所在的那一格是液体（游泳 / 站在浅水里）。
     *
     * <p>这种情况先上岸，水里既不垫高也不搭桥。</p>
     */
    private static boolean isWet(BuildWorld world, GridPos mob) {
        if (world.isLiquid(mob.x(), mob.y(), mob.z())) {
            return true;
        }
        // 站在水面方块上（极少见，但同样不该从水里往上搭）。
        return world.isLiquid(mob.x(), mob.y() - 1, mob.z());
    }

    /**
     * 在附近找一块“干燥、能站人”的落脚点，用来上岸。
     *
     * <p>优先同高度，其次下一格（跳下来），最后上一格（爬上去）；
     * 必须脚下有实心方块、脚与头通透、不是液体、也没被别的生物占着。</p>
     *
     * @return 上岸目标；找不到时返回 {@code null}
     */
    private static GridPos findShore(BuildWorld world, GridPos mob) {
        GridPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SHORE_RADIUS; dx <= SHORE_RADIUS; dx++) {
            for (int dz = -SHORE_RADIUS; dz <= SHORE_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy : new int[] {0, -1, 1, -2}) {
                    int x = mob.x() + dx;
                    int y = mob.y() + dy;
                    int z = mob.z() + dz;
                    if (isDryStand(world, x, y, z)) {
                        double score = Math.hypot(dx, dz) + Math.abs(dy) * 2.0D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new GridPos(x, y, z);
                        }
                        break; // 同一列只取最合适的那个高度
                    }
                }
            }
        }
        return best;
    }

    /** 该格能否当“上岸”的落脚点：脚下实心、脚与头通透、不是液体、没有别的生物。 */
    private static boolean isDryStand(BuildWorld world, int x, int y, int z) {
        if (!world.isLoaded(x, y - 1, z) || !world.isLoaded(x, y, z) || !world.isLoaded(x, y + 1, z)) {
            return false;
        }
        if (world.isLiquid(x, y, z) || world.isLiquid(x, y + 1, z) || world.isLiquid(x, y - 1, z)) {
            return false;
        }
        if (!world.isPassable(x, y, z) || !world.isPassable(x, y + 1, z)) {
            return false;
        }
        if (world.isPassable(x, y - 1, z)) {
            return false; // 脚下没有实心方块
        }
        return !world.hasEntity(x, y, y + 1, z);
    }

    /**
     * 规划下一步。
     *
     * @param query 几何信息与配置上限
     * @param world 只读世界视图
     * @return 规划结果；{@link Plan#step()} 的坐标可直接交给放置执行器
     */
    public static Plan plan(Query query, BuildWorld world) {
        if (query == null || world == null || query.mobFeet() == null || query.targetFeet() == null) {
            return Plan.none("缺少坐标或世界", null);
        }
        GridPos mob = query.mobFeet();
        GridPos target = query.targetFeet();
        int rise = target.y() - mob.y();
        double horizontal = Math.hypot(target.x() - mob.x(), target.z() - mob.z());

        // 目标就在身边、也不在明显高处：交给原版近战，不放方块。
        // 这一条要排在“上岸”之前：目标就在水里贴着打的时候，不该先游上岸。
        if (horizontal < MELEE_RANGE && rise < MIN_PILLAR_RISE) {
            return Plan.none("目标就在身边，交给原版近战", null);
        }

        // 泡在水里 / 浮在水面上：先上岸，不要在水里搭方块。
        // 否则一群怪会围着目标正下方漂着，谁也搭不起来（在水里垫的方块也会被水覆盖）。
        if (isWet(world, mob)) {
            if (isWet(world, target)) {
                // 目标也在水里：直接游过去打，上岸反而会走远。
                return Plan.none("目标在水里，直接游过去", null);
            }
            GridPos shore = findShore(world, mob);
            if (shore != null) {
                return Plan.of(Step.walk(shore), null);
            }
            return Plan.none("在水里，附近没有可以上岸的干燥落脚点", null);
        }


        // 需要抬高（或抬高后才够得着）：楼梯 → 垫高 → 搭桥。
        if (rise >= MIN_PILLAR_RISE) {
            if (!query.onGround()) {
                return Plan.none("腾空中，等落地再搭", query.lane());
            }
            // 横向很远、高度不算夸张：楼梯能同时前进与上升，比“先竖着垫、再横着铺”省方块。
            if (query.stairEnabled() && shouldClimbByStairs(query, rise, horizontal)) {
                StairStep stair = stair(query, world);
                if (stair.step().isAction() || stair.fallback() == StairFallback.NONE) {
                    return Plan.of(stair.step(), null);
                }
                // 这一级过不去（墙 / 有人 / 没净空）：用垫高绕过去。
            }
            if (query.pillarEnabled()) {
                // 头顶（脚上方第 2 格）被方块压着、而挖掉它这一列就能继续往上垫：
                // 有镐子的怪物优先把它挖掉，而不是绕到旁边另起一列。
                if (query.canDigUp() && planWantsToMineCeiling(query, world)) {
                    return Plan.none("头顶被方块挡住，先挖掉它再垫高", null);
                }
                Plan climb = climb(query, world);
                if (climb != null) {
                    return climb;
                }
            }
            if (query.remainingPillarSteps() == 0) {
                return Plan.none("单次追击的垫高格数已用完", null);
            }
            return Plan.none(query.pillarEnabled()
                    ? "附近没有可以垫高的空列" : "目标在高处，但未开启垫高", null);
        }

        if (!query.bridgeEnabled()) {
            return Plan.none("未开启搭桥", null);
        }
        return Plan.of(bridge(query, world), null);
    }

    /**
     * 按“横向多远、高度多高”决定是搭楼梯还是垫高。
     *
     * <ul>
     *   <li>横向越远、高度越有限 → 楼梯（一级同时前进一格 + 抬高一级，方块利用率最高）；</li>
     *   <li>高度差过大 / 横向太近 → 垫高（楼梯会一路朝前冲过头，反而更难接近目标）。</li>
     * </ul>
     */
    private static boolean shouldClimbByStairs(Query query, int rise, double horizontal) {
        if (horizontal <= query.pillarNearDistance()) {
            return false;
        }
        if (horizontal < query.stairMinDistance()) {
            return false;
        }
        return rise < query.pillarFarHeight();
    }

    // ------------------------------------------------------------------
    // 楼梯
    // ------------------------------------------------------------------

    /**
     * 搭一级楼梯：方块放在“前方与脚同高”的那一格，怪物跳上去就前进一格、抬高一级。
     *
     * <p>只朝目标方向、沿水平差更大的那条轴搭，因此楼梯是一条笔直的斜线，
     * 不会出现左右乱垫的锯齿。</p>
     */
    private static StairStep stair(Query query, BuildWorld world) {
        GridPos mob = query.mobFeet();
        GridPos target = query.targetFeet();
        int dx = target.x() - mob.x();
        int dz = target.z() - mob.z();
        if (dx == 0 && dz == 0) {
            // 已经在目标正下方：只剩高度差，交给垫高。
            return StairStep.pillar("已经和目标同一列，改为垫高");
        }
        if (query.bridgeMaxLength() > 0 && Math.hypot(dx, dz) > query.bridgeMaxLength()) {
            return StairStep.stop("离目标太远，不搭长楼梯");
        }

        int stepX = 0;
        int stepZ = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            stepX = Integer.signum(dx);
        } else {
            stepZ = Integer.signum(dz);
        }

        GridPos front = new GridPos(mob.x() + stepX, mob.y(), mob.z() + stepZ);
        if (!world.isLoaded(front.x(), front.y(), front.z())) {
            return StairStep.stop("前方不可读");
        }
        if (world.isLiquid(front.x(), front.y(), front.z())) {
            // 楼梯格是水：不往水里垫，交给原版绕到岸上再来。
            return StairStep.stop("前方是水，不搭楼梯");
        }
        if (!world.isReplaceable(front.x(), front.y(), front.z())) {
            GridPos above = front.offset(0, 1, 0);
            if (!world.isLoaded(above.x(), above.y(), above.z())
                    || !world.isPassable(above.x(), above.y(), above.z())) {
                // 前方是一堵两格以上的墙：站着跳不上去，用垫高绕到墙顶。
                return StairStep.pillar("前方是墙，改用垫高绕过");
            }
            // 前方正好是一级现成的台阶：跳上去就行，别再垫方块。
            return StairStep.walk(above);
        }

        // 楼梯格上方要放得下怪物的身体与脑袋（跳上去之后脚就在这一格之上）。
        GridPos feet = front.offset(0, 1, 0);
        GridPos head = front.offset(0, 2, 0);
        if (!world.isLoaded(feet.x(), feet.y(), feet.z()) || !world.isPassable(feet.x(), feet.y(), feet.z())
                || !world.isLoaded(head.x(), head.y(), head.z()) || !world.isPassable(head.x(), head.y(), head.z())) {
            return StairStep.pillar("这一级上方没有站人的空间");
        }
        if (world.hasAnyEntity(front.x(), front.y(), head.y(), front.z())) {
            // 楼梯格要放方块，任何生物（包括目标）站在里面都不行。
            return StairStep.pillar("这一级有生物占着");
        }
        return StairStep.place(front);
    }

    // ------------------------------------------------------------------
    // 垫高
    // ------------------------------------------------------------------

    /**
     * 这一轮垫高要垫到的目标高度（受单次追击的垫高预算限制）。
     *
     * @return 目标高度对应的方块 Y；已经不需要再往上时返回一个 ≤ 脚所在高度的值
     */
    private static int climbTargetY(Query query) {
        GridPos mob = query.mobFeet();
        int targetY = query.targetFeet().y();
        int budget = query.remainingPillarSteps();
        return budget < 0 ? targetY : Math.min(targetY, mob.y() + budget);
    }

    /**
     * 是不是“头顶那一格挡住垫高、而挖掉它就能继续往上垫”。
     *
     * <p>只有自己这一列正好是唯一出路时才值得先挖：如果这一列从第 3 格起还有别的挡路方块，
     * 挖掉头顶那块也没用，那就还是换列（由 {@code chooseLane} 处理）。</p>
     */
    private static boolean planWantsToMineCeiling(Query query, BuildWorld world) {
        GridPos mob = query.mobFeet();
        int climbY = climbTargetY(query);
        if (climbY <= mob.y()) {
            return false;
        }
        GridPos head = new GridPos(mob.x(), mob.y() + 2, mob.z());
        if (!world.isLoaded(head.x(), head.y(), head.z()) || world.isPassable(head.x(), head.y(), head.z())) {
            // 头顶本来就空着：没有可挖的东西。
            return false;
        }
        // 假装头顶那一格已经挖掉了，看这一列能不能一路垫到目标高度。
        return columnShapeOk(world, new BuildLane(mob.x(), mob.z()), mob.y(), climbY, head);
    }

    /**
     * 垫高阶段：确定垫高列，决定“先走过去”还是“就地垫一格”。
     *
     * @return 需要垫高 / 移动时的规划；已经不需要（或无法）垫高时返回 {@code null}
     */
    private static Plan climb(Query query, BuildWorld world) {
        GridPos mob = query.mobFeet();
        int climbY = climbTargetY(query);
        if (climbY <= mob.y()) {
            // 垫高预算用尽：不再垫，交给后面的搭桥 / 原版寻路。
            return null;
        }

        BuildLane lane = query.lane();
        if (lane != null && (query.isRejected(lane)
                || !columnShapeOk(world, lane, mob.y(), climbY)
                || world.hasEntity(lane.x(), mob.y(), climbY + 1, lane.z()))) {
            // 整列校验（不是只看下一格）：否则会出现“垫了两格才发现上面被天花板挡住”，
            // 怪物站在 1×1 的柱子上、既上不去也回不来。
            lane = null;
        }
        if (lane == null) {
            lane = chooseLane(query, world, climbY);
        }
        if (lane == null) {
            return null;
        }
        if (lane.x() != mob.x() || lane.z() != mob.z()) {
            // 选定的列不在脚下：先走到列底，到位后再垫。
            return Plan.of(Step.relocate(new GridPos(lane.x(), mob.y(), lane.z())), lane);
        }
        return Plan.of(Step.pillarUp(new GridPos(mob.x(), mob.y(), mob.z())), lane);
    }

    /**
     * 挑一列适合垫高的空列。
     *
     * <p>候选是脚下附近同一高度的列，按“离目标更近（桥更短）+ 自己少走路”排序，
     * 先做便宜的方块校验，再对排在最前面的几列做一次实体查询。
     * 因此“脚下这一列本来就空”这种常见情况只会读掉几格方块。</p>
     */
    private static BuildLane chooseLane(Query query, BuildWorld world, int climbY) {
        GridPos mob = query.mobFeet();
        GridPos target = query.targetFeet();
        int radius = Math.max(0, query.laneRadius());

        record Candidate(BuildLane lane, double score) {
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = mob.x() + dx;
                int z = mob.z() + dz;
                BuildLane lane = new BuildLane(x, z);
                if (query.isRejected(lane)) {
                    // 这一列之前走不过去 / 放不下：本次追击不再考虑。
                    continue;
                }
                double toTarget = Math.hypot(x - target.x(), z - target.z());
                double walk = Math.hypot(dx, dz);
                candidates.add(new Candidate(lane, toTarget + WALK_PENALTY * walk));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score)
                .thenComparingInt(candidate -> candidate.lane().x())
                .thenComparingInt(candidate -> candidate.lane().z()));

        int entityChecks = 0;
        int columnChecks = 0;
        for (Candidate candidate : candidates) {
            if (columnChecks >= MAX_COLUMN_CHECKS || entityChecks >= MAX_ENTITY_CHECKS) {
                break;
            }
            BuildLane lane = candidate.lane();
            columnChecks++;
            if (!columnShapeOk(world, lane, mob.y(), climbY)) {
                continue;
            }
            entityChecks++;
            if (world.hasEntity(lane.x(), mob.y(), climbY + 1, lane.z())) {
                // 这一列里有别的生物：抬上去就会和同伴叠在一起，换一列。
                continue;
            }
            return lane;
        }
        return null;
    }

    /**
     * 已经选定的列还能不能继续用：只校验“下一格”与剩余高度区间里的实体，
     * 避免每次扫描都把整列读一遍。
     */
    private static boolean laneStillUsable(BuildWorld world, BuildLane lane, int fromY, int toY) {
        int x = lane.x();
        int z = lane.z();
        if (!world.isLoaded(x, fromY, z) || !world.isReplaceable(x, fromY, z)
                || world.isLiquid(x, fromY, z)) {
            return false;
        }
        if (!world.isLoaded(x, fromY + 1, z) || !world.isPassable(x, fromY + 1, z)) {
            return false;
        }
        return !world.hasEntity(x, fromY, toY + 1, z);
    }

    /** 整列形态校验：能垫、能站、头顶不被挡死。 */
    private static boolean columnShapeOk(BuildWorld world, BuildLane lane, int fromY, int toY) {
        return columnShapeOk(world, lane, fromY, toY, null);
    }

    /**
     * 整列形态校验，可“假装”某一格已经被挖掉（{@code ignored}），
     * 用来判断“挖掉这一格之后这一列还通不通”。
     */
    private static boolean columnShapeOk(BuildWorld world, BuildLane lane, int fromY, int toY, GridPos ignored) {
        int x = lane.x();
        int z = lane.z();
        if (toY <= fromY) {
            return false;
        }
        // 列底要站得住：脚下必须有支撑，否则走过去的怪物会掉下去。
        if (!world.isLoaded(x, fromY - 1, z) || world.isPassable(x, fromY - 1, z)) {
            return false;
        }
        for (int y = fromY; y < toY; y++) {
            if (!cellReplaceable(world, x, y, z, ignored) || (!isIgnored(x, y, z, ignored) && world.isLiquid(x, y, z))) {
                return false;
            }
            if (!cellPassable(world, x, y + 1, z, ignored)) {
                return false;
            }
        }
        // 爬到最高一级后还要放得下脑袋。
        return cellPassable(world, x, toY + 1, z, ignored);
    }

    private static boolean isIgnored(int x, int y, int z, GridPos ignored) {
        return ignored != null && ignored.x() == x && ignored.y() == y && ignored.z() == z;
    }

    private static boolean cellReplaceable(BuildWorld world, int x, int y, int z, GridPos ignored) {
        if (isIgnored(x, y, z, ignored)) {
            return true;
        }
        return world.isLoaded(x, y, z) && world.isReplaceable(x, y, z);
    }

    private static boolean cellPassable(BuildWorld world, int x, int y, int z, GridPos ignored) {
        if (isIgnored(x, y, z, ignored)) {
            return true;
        }
        return world.isLoaded(x, y, z) && world.isPassable(x, y, z);
    }

    // ------------------------------------------------------------------
    // 搭桥
    // ------------------------------------------------------------------

    /**
     * 搭桥阶段：只朝目标方向、沿水平差更大的那条轴铺一格。
     *
     * <p>先消掉较大的水平差（例如先走 X 再走 Z），所以整条桥是“L 形直线”，
     * 不会出现斜向的锯齿，也不会往身后 / 侧面找地方放。</p>
     */
    private static Step bridge(Query query, BuildWorld world) {
        GridPos mob = query.mobFeet();
        GridPos target = query.targetFeet();
        int dx = target.x() - mob.x();
        int dz = target.z() - mob.z();
        if (dx == 0 && dz == 0) {
            return Step.none("已经在目标所在列，不需要横向搭桥");
        }
        if (query.bridgeMaxLength() > 0 && Math.hypot(dx, dz) > query.bridgeMaxLength()) {
            return Step.none("离目标太远，不搭天桥");
        }

        // 只走主轴向：水平差大的那条轴优先，两者相等时固定选 X。
        int stepX = 0;
        int stepZ = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            stepX = Integer.signum(dx);
        } else {
            stepZ = Integer.signum(dz);
        }

        GridPos front = new GridPos(mob.x() + stepX, mob.y(), mob.z() + stepZ);
        GridPos head = front.offset(0, 1, 0);
        if (!world.isLoaded(front.x(), front.y(), front.z()) || !world.isLoaded(head.x(), head.y(), head.z())) {
            return Step.none("前方不可读");
        }
        if (!world.isPassable(front.x(), front.y(), front.z())) {
            // 前方是方块：如果只高出一格，怪物自己就能跳上去（原版能跳 1 格），别在这里“就差一格”停住。
            if (world.isPassable(head.x(), head.y(), head.z())
                    && !world.isLiquid(head.x(), head.y(), head.z())) {
                return Step.walk(head);
            }
            // 两格以上的墙：不硬搭，交给原版绕行或挖掘行为。
            return Step.none("前方过不去，不搭桥");
        }
        if (!world.isPassable(head.x(), head.y(), head.z())) {
            // 前方只有一格高：铺了也走不过去。
            return Step.none("前方高度不够，不搭桥");
        }
        if (world.isLiquid(front.x(), front.y(), front.z())) {
            // 前方是水：不往水里搭桥（铺下去也会被水覆盖），交给原版从岸边绕行。
            return Step.none("前方是水，不搭桥");
        }

        GridPos floor = front.offset(0, -1, 0);
        if (!world.isLoaded(floor.x(), floor.y(), floor.z())) {
            return Step.none("前方脚下不可读");
        }
        if (world.isLiquid(floor.x(), floor.y(), floor.z())) {
            // 前方脚下是水面：不在液体上铺桥，理由是铺了也立刻被水冲掉 / 根本立不住。
            return Step.none("前方脚下是水，不搭桥");
        }

        // 前方脚下有实地 → 直接走过去（这里要给 WALK：原版寻路常常因为“目标还在沟壑对面、
        // 根本到不了”而不给移动指令，旧版本就是在这里干站着的）。
        if (!world.isReplaceable(floor.x(), floor.y(), floor.z())) {
            return Step.walk(front);
        }

        // 前方脚下是空的：先看**落差有多小**。原版自己就能吃下 ≤3 格落差，
        // 这种小台阶 / 土坡直接走下去就行，不该铺路（旧版本每个小落差都垫一格，
        // 在野外看着就是“在地上水平走路也在铺路”）。
        int drop = frontDrop(world, front);
        if (drop >= 0 && drop <= MAX_SAFE_DROP) {
            return Step.walk(front);
        }

        // 目标明显更低、而且前方确实是深沟：往下搭一层（落点 = 正前方、低两格，
        // 站上去正好一个 1 格落差）。
        if (mob.y() - target.y() >= query.verticalDrop()) {
            GridPos lower = floor.offset(0, -1, 0);
            if (!world.isLoaded(lower.x(), lower.y(), lower.z())) {
                return Step.none("前下方不可读");
            }
            if (world.isLiquid(lower.x(), lower.y(), lower.z())) {
                return Step.none("前下方是水，不往下搭");
            }
            if (!world.isReplaceable(lower.x(), lower.y(), lower.z())) {
                return Step.none("前下方不能放方块");
            }
            if (world.hasAnyEntityAt(lower.x(), lower.y(), lower.z())) {
                return Step.none("落点有生物，先让开");
            }
            return Step.bridge(lower);
        }

        if (world.hasEntityAt(front.x(), front.y(), front.z())) {
            // 已经有别的生物站在要铺的这一格上：让开，避免把同伴挤在一起。
            // 注意：追击目标不算（我们就是冲着它去的，最后几格往往正好卡在它身边）。
            return Step.none("前方站着别的生物，先让开");
        }
        if (world.hasAnyEntityAt(floor.x(), floor.y(), floor.z())) {
            // 方块要放进这一格：任何生物（包括目标）都不能在里面。
            return Step.none("落点有生物，先让开");
        }
        return Step.bridge(floor);
    }

    /**
     * 前方脚下往下的落差有几格（{@code 0} = 紧贴地面）。
     *
     * @return 落差格数；{@code -1} 表示“太深 / 看不到底 / 下面是水”，这种情况才值得搭
     */
    private static int frontDrop(BuildWorld world, GridPos front) {
        for (int depth = 1; depth <= MAX_SAFE_DROP + 1; depth++) {
            GridPos probe = new GridPos(front.x(), front.y() - depth, front.z());
            if (!world.isLoaded(probe.x(), probe.y(), probe.z())) {
                return -1;
            }
            if (world.isLiquid(probe.x(), probe.y(), probe.z())) {
                return -1;
            }
            if (!world.isPassable(probe.x(), probe.y(), probe.z())) {
                // 踩到实地：落差 = depth - 1 格。
                return depth - 1;
            }
        }
        return -1;
    }
}
