package studio.liuye.mobinteract.mob;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.backpack.BackpackFactory;
import studio.liuye.mobinteract.backpack.MobBackpack;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.placement.BuildLane;

/**
 * 单个怪物实例的轻量运行期状态：冷却、背包、单次追击的放置计数。
 *
 * <p>状态只存在内存中，怪物被移除时随之丢弃（{@link MobRegistry} 负责回收）。</p>
 */
public final class MobProfile {

    /** 垫高列黑名单的容量上限：满了就整表清空，给旧列重新机会。 */
    private static final int MAX_REJECTED_LANES = 64;

    private final UUID entityId;

    private volatile long miningCooldownUntil;
    private volatile long placementCooldownUntil;

    private volatile boolean backpackDecided;
    private volatile MobBackpack backpack;

    /**
     * 是否通过了 {@code equip.yml} 的“出生装备”闸门。
     *
     * <p>没过闸门的怪物在任何路径上都不会再抽背包（见 {@link #decideBackpack}），
     * 这样“带镐子 + 带方块”的比例加起来才不会超过配置值。</p>
     */
    private volatile boolean equipGranted;
    /** 镐子闸门是否已经掷过骰（避免同一只怪反复掷）。 */
    private volatile boolean equipRolled;

    /** 是否正在挖掘：挖掘与放置互斥（挖掘时只盯着方块，不会同时铺方块）。 */
    private volatile boolean mining;

    private volatile UUID chaseTarget;
    private volatile int placementsThisChase;

    private volatile boolean pickaxeChecked;
    private volatile boolean grantedPickaxe;

    /** 单次追击内的竖直垫高格数。 */
    private volatile int pillarSteps;

    /** 本次追击选定的垫高列（先垫高、再横向搭桥）；{@code null} = 还没选 / 已用完。 */
    private volatile BuildLane buildLane;

    /** 本次追击里已经放弃的垫高列（走不过去 / 放不下），避免反复挑同一个位置。 */
    private final Set<BuildLane> rejectedLanes = ConcurrentHashMap.newKeySet();

    /** 上次“走到垫高列 / 走上已铺好的路”的时间戳（毫秒），用于节流寻路调用。 */
    private volatile long lastAdvanceAt;

    /** 最近一次行为状态，供 /mobinteract inspect 诊断“为什么不动”。 */
    private volatile String lastStatus = "";

    /** 当前手持（副手展示）的放置条目。 */
    private volatile PlacementEntry heldEntry;
    /** 副手物品是否由本插件管理。 */
    private volatile boolean handManaged;
    /** 上次“让位避挤”的时间戳（毫秒）。 */
    private volatile long lastSpreadAt;
    /** 上次“绕行”的时间戳（毫秒）。 */
    private volatile long lastDetourAt;
    /** 是否正在执行挖掘 / 放置（此时不要让位或绕行，避免打断动作）。 */
    private volatile boolean busy;
    /** 上次“上升侦察”的时间戳（毫秒），用于节流寻路计算。 */
    private volatile long lastScoutAt;

    public MobProfile(UUID entityId) {
        this.entityId = entityId;
    }

    public UUID entityId() {
        return entityId;
    }

    public long miningCooldownUntil() {
        return miningCooldownUntil;
    }

    public void setMiningCooldownUntil(long timestamp) {
        this.miningCooldownUntil = timestamp;
    }

    public boolean miningOnCooldown(long now) {
        return now < miningCooldownUntil;
    }

    public long placementCooldownUntil() {
        return placementCooldownUntil;
    }

    public void setPlacementCooldownUntil(long timestamp) {
        this.placementCooldownUntil = timestamp;
    }

    public boolean placementOnCooldown(long now) {
        return now < placementCooldownUntil;
    }

    /** 背包对象；未决定或没有背包时为 {@code null}。 */
    public MobBackpack backpack() {
        return backpack;
    }

    public boolean backpackDecided() {
        return backpackDecided;
    }

    /** 记录“这只怪是否通过了出生装备闸门”（只掷一次骰）。 */
    public synchronized boolean rollEquip(double chance, java.util.Random random) {
        if (!equipRolled) {
            equipRolled = true;
            equipGranted = random.nextDouble() < chance;
        }
        return equipGranted;
    }

    /** 是否已经通过出生装备闸门（没过就既不给镐子也不给方块）。 */
    public boolean equipGranted() {
        return equipGranted;
    }

    /** 是否正在挖掘：挖掘与放置互斥，挖掘期间不做放置决策。 */
    public boolean mining() {
        return mining;
    }

    /** 标记挖掘状态（由挖掘行为在开始 / 结束时设置）。 */
    public void setMining(boolean value) {
        this.mining = value;
    }

    /**
     * 首次需要时决定是否拥有背包（“怪物生成时或首次需要时”语义），
     * 之后不会再改变，避免重复抽取。
     */
    public synchronized MobBackpack decideBackpack(PlacementSettings settings, java.util.Random random) {
        if (backpackDecided) {
            return backpack;
        }
        backpackDecided = true;
        if (!equipGranted || !settings.hasEntries() || random.nextDouble() >= settings.backpackChance()) {
            backpack = null;
        } else {
            backpack = BackpackFactory.create(settings, random);
        }
        return backpack;
    }

    /**
     * 记录当前追击目标，目标变化时重置单次追击放置计数。
     */
    public void noteChaseTarget(UUID targetId) {
        if (!java.util.Objects.equals(chaseTarget, targetId)) {
            chaseTarget = targetId;
            placementsThisChase = 0;
            pillarSteps = 0;
            buildLane = null;
            rejectedLanes.clear();
        }
    }

    /**
     * 拿到可写的背包：没有就建一个空的。
     *
     * <p>“是否有背包”那次掷骰只决定<b>初始方块</b>；怪物主动捡到方块时必须能存下来，
     * 否则拾取会变成“捡了也没用”。</p>
     */
    public synchronized MobBackpack backpackOrCreate() {
        if (backpack == null) {
            backpack = BackpackFactory.empty();
        }
        backpackDecided = true;
        return backpack;
    }

    public UUID chaseTarget() {
        return chaseTarget;
    }

    public int placementsThisChase() {
        return placementsThisChase;
    }

    public void incrementPlacementsThisChase() {
        placementsThisChase++;
    }

    /** 是否已经为这只怪物做过“是否自带镐子”的抽签。 */
    public boolean pickaxeChecked() {
        return pickaxeChecked;
    }

    public void markPickaxeChecked() {
        this.pickaxeChecked = true;
    }

    /** 这只怪物的镐子是否由本插件发放（决定掉落策略）。 */
    public boolean grantedPickaxe() {
        return grantedPickaxe;
    }

    public void markPickaxeGranted() {
        this.grantedPickaxe = true;
    }

    public int pillarSteps() {
        return pillarSteps;
    }

    public void incrementPillarSteps() {
        pillarSteps++;
    }

    /** 本次追击选定的垫高列；{@code null} 表示还没选或已经不需要。 */
    public BuildLane buildLane() {
        return buildLane;
    }

    public void setBuildLane(BuildLane lane) {
        this.buildLane = lane;
    }

    /** 本次追击已经放弃的垫高列（只读视图），供规划时跳过。 */
    public Set<BuildLane> rejectedLanes() {
        return rejectedLanes;
    }

    /**
     * 记住这一列不能再用（走不过去 / 放不下），本次追击内不再尝试。
     *
     * <p>黑名单必须能被回收：{@code rejectedLanes} 原本只有在“追击目标变了”时才清空，
     * 而玩家只要站着不动，每次垫高失败就会拉黑一列——半径 3 一共 49 列，
     * 按每秒两次的节奏二十多秒就耗光了，之后 {@code chooseLane} 永远返回 null，
     * 这只怪在整场追击里再也不会垫高，而 {@code /mobinteract inspect} 只会说
     * “附近没有可以垫高的空列”，服主根本查不出原因。</p>
     */
    public void rejectLane(BuildLane lane) {
        if (lane == null) {
            return;
        }
        if (rejectedLanes.size() >= MAX_REJECTED_LANES) {
            // 攒满了说明整片区域都试过了：清空重来，给之前失败的列再一次机会。
            rejectedLanes.clear();
        }
        rejectedLanes.add(lane);
        if (buildLane != null && buildLane.equals(lane)) {
            buildLane = null;
        }
    }

    public long lastAdvanceAt() {
        return lastAdvanceAt;
    }

    public void setLastAdvanceAt(long timestamp) {
        this.lastAdvanceAt = timestamp;
    }

    public String lastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String status) {
        this.lastStatus = status == null ? "" : status;
    }

    public PlacementEntry heldEntry() {
        return heldEntry;
    }

    public void setHeldEntry(PlacementEntry entry) {
        this.heldEntry = entry;
    }

    public boolean handManaged() {
        return handManaged;
    }

    public void setHandManaged(boolean managed) {
        this.handManaged = managed;
    }

    public long lastSpreadAt() {
        return lastSpreadAt;
    }

    public void setLastSpreadAt(long timestamp) {
        this.lastSpreadAt = timestamp;
    }

    public long lastDetourAt() {
        return lastDetourAt;
    }

    public void setLastDetourAt(long timestamp) {
        this.lastDetourAt = timestamp;
    }

    public boolean busy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public long lastScoutAt() {
        return lastScoutAt;
    }

    public void setLastScoutAt(long timestamp) {
        this.lastScoutAt = timestamp;
    }
}
