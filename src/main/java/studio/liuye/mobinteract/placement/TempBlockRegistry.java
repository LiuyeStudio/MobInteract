package studio.liuye.mobinteract.placement;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.util.BlockPos;

/**
 * 怪物放置方块的登记表。
 *
 * <p>三个用途：</p>
 * <ol>
 *   <li><b>存在时间</b>：{@code block-lifetime-seconds &gt; 0} 时记录到期时间，
 *       由**全局清扫器**（每 20 tick 一次）到点删除；删除前再次比对方块类型，
 *       避免误删玩家后来放的东西。区块没加载时会把这条延后 5 秒再试，绝不丢记录；</li>
 *   <li><b>来源标记</b>：无论存在时间是否为 0，都会记录“这块是怪物放的”，
 *       供挖掘逻辑排除，避免有镐子的怪物把同伴刚搭好的桥 / 台阶拆掉；</li>
 *   <li><b>卸载清理</b>：插件被卸载 / 关服时把方块全删掉；同时把待删清单写到
 *       {@code pending-blocks.txt}，下次启动再兜底清一次（崩溃 / Folia 关服来不及执行都能覆盖）。</li>
 * </ol>
 *
 * <p>为避免永久方块无限累积，登记表设有容量上限，超出后按插入顺序淘汰最旧的记录。</p>
 */
public final class TempBlockRegistry {

    /** 登记表容量上限（仅用于“来源标记”，超出后淘汰最旧记录）。 */
    private static final int MAX_TRACKED = 50_000;

    /** 卸载时写下的“待删除清单”，下次启动兜底清理。 */
    private static final String JOURNAL_FILE = "pending-blocks.txt";

    /** 到期时区块没加载：延后这么多毫秒再试。 */
    private static final long RETRY_DELAY_MILLIS = 5_000L;

    private record TrackedBlock(UUID worldId, int x, int y, int z, Material material, long expireAtMillis,
            int lifetimeSeconds) {
        String key() {
            return makeKey(worldId, x, y, z);
        }

        String toLine() {
            return worldId + " " + x + " " + y + " " + z + " " + material.name();
        }

        TrackedBlock withExpiry(long newExpireAt) {
            return new TrackedBlock(worldId, x, y, z, material, newExpireAt, lifetimeSeconds);
        }
    }

    private final MobInteractPlugin plugin;
    private final Map<String, TrackedBlock> tracked = new ConcurrentHashMap<>();
    /** 插入顺序队列，用于在超过容量时淘汰最旧记录。 */
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();

    /**
     * 到期时间表（毫秒；0 = 永久，不入表）。全局清扫器按时间顺序取出。
     *
     * <p>{@link PriorityQueue} <b>不是线程安全的</b>，而这里的读写天生跨线程：
     * 写入来自“方块所在区域线程”（放置 / 续期），读取来自全局清扫线程。
     * 并发 add 触发扩容时可能破坏堆结构（抛 {@code ArrayIndexOutOfBoundsException}）
     * 或者更隐蔽地静默丢条目——那块方块就再也不到期，永远留在地图上。
     * 所以所有访问都走 {@code expiryLock}。</p>
     */
    private final PriorityQueue<Expiry> expiries = new PriorityQueue<>(Comparator.comparingLong(Expiry::atMillis));
    private final Object expiryLock = new Object();

    private record Expiry(String key, long atMillis) {
    }

    /** 区块标识：按世界 UUID + 区块坐标精确分组，不拼位域（拼位域会碰撞）。 */
    private record ChunkRef(UUID worldId, int chunkX, int chunkZ) {
    }

    public TempBlockRegistry(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动全局清扫器（每 20 tick 扫一次到期方块）。由插件启用时调用一次。 */
    public void startSweeper() {
        plugin.schedulers().globalTimer(20L, this::sweep);
    }

    /**
     * 清扫一次：把已经到期的方块删掉。
     *
     * <p>为什么不用“每块一个延迟任务”：Folia 上区域暂停 / 任务被清掉时就再也没人管了，
     * 而且到期那一刻如果区块正好没加载，旧实现会把记录直接丢掉、方块永远留下。
     * 现在改成时间表 + 定期清扫：区块没加载就把这条往后挪 5 秒再试。</p>
     */
    void sweep() {
        long now = System.currentTimeMillis();
        Map<ChunkRef, List<TrackedBlock>> byChunk = new HashMap<>();
        for (Expiry next : drainDue(now)) {
            TrackedBlock entry = tracked.get(next.key());
            if (entry == null) {
                continue;   // 已经被删 / 被替换
            }
            if (entry.expireAtMillis() <= 0L) {
                // 这一格后来被改登记成“永久方块”（lifetime 改成 0）：旧到期条目作废。
                continue;
            }
            if (entry.expireAtMillis() > next.atMillis()) {
                // 同一格被重新登记过（有更晚的到期时间）：这条是旧的，丢掉。
                continue;
            }
            World world = plugin.getServer().getWorld(entry.worldId());
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
                // 区块没加载：延后 5 秒再试，绝不丢记录。
                enqueueExpiry(next.key(), now + RETRY_DELAY_MILLIS);
                continue;
            }
            byChunk.computeIfAbsent(chunkKey(entry), key -> new ArrayList<>()).add(entry);
        }
        for (List<TrackedBlock> entries : byChunk.values()) {
            TrackedBlock first = entries.get(0);
            World world = plugin.getServer().getWorld(first.worldId());
            if (world == null) {
                continue;
            }
            boolean submitted = plugin.schedulers().region(world, first.x(), first.z(), () -> {
                for (TrackedBlock entry : entries) {
                    // 提交到执行之间这一格可能被重新放置过：到期时间变了就说明手上的条目已过期。
                    TrackedBlock current = tracked.get(entry.key());
                    if (current == null || current.expireAtMillis() != entry.expireAtMillis()) {
                        continue;
                    }
                    if (hasEntityStandingOn(world, entry)) {
                        // 方块上还站着生物（包括正在过桥的怪物）：再延一个完整的放置时间。
                        extend(entry);
                    } else {
                        expire(world, entry);
                    }
                }
            });
            if (!submitted) {
                // 区域调度拒绝（区域正在关闭 / 迁移）：把这批重新排进到期表，别让记录变成孤儿。
                for (TrackedBlock entry : entries) {
                    retryLater(entry);
                }
            }
        }
    }

    /** 取走所有已到期的条目（加锁，避免与区域线程的写入竞争）。 */
    private List<Expiry> drainDue(long now) {
        List<Expiry> due = new ArrayList<>();
        synchronized (expiryLock) {
            Expiry next;
            while ((next = expiries.peek()) != null && next.atMillis() <= now) {
                due.add(expiries.poll());
            }
        }
        return due;
    }

    private void enqueueExpiry(String key, long atMillis) {
        synchronized (expiryLock) {
            expiries.add(new Expiry(key, atMillis));
        }
    }

    /**
     * 方块正上方是否站着生物（活着、有效）。
     *
     * <p>只检查方块顶面以上那 1.2 格：脚下踩着这块方块的生物都算，
     * 避免把“方块下方洞穴里的生物”也当成占用。</p>
     */
    private boolean hasEntityStandingOn(World world, TrackedBlock entry) {
        try {
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            org.bukkit.util.BoundingBox box = block.getBoundingBox();
            org.bukkit.util.BoundingBox above = new org.bukkit.util.BoundingBox(
                    box.getMinX(), box.getMaxY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY() + 1.2D, box.getMaxZ());
            java.util.Collection<org.bukkit.entity.Entity> found = world.getNearbyEntities(above);
            if (plugin.settings().mining().debug()) {
                plugin.getLogger().info("[debug] 续期检查 " + entry.x() + "," + entry.y() + "," + entry.z()
                        + " box=" + above + " 生物=" + found.size());
                for (org.bukkit.entity.Entity entity : found) {
                    plugin.getLogger().info("[debug]   候选 " + entity.getType() + " @ "
                            + entity.getLocation().toVector());
                }
            }
            for (org.bukkit.entity.Entity entity : found) {
                if (entity instanceof org.bukkit.entity.LivingEntity living && living.isValid()
                        && !living.isDead()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable throwable) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "续期检查失败：查询方块上方生物出错", throwable);
            // 读不到就当成“上面站着生物”：宁可多留一个方块，也不要把生物脚下的方块抽走（会让人掉下去 / 卡在墙里）。
            return true;
        }
    }

    /** 把一条登记的到期时间再延一个周期（只延不缩）。 */
    private void extend(TrackedBlock entry) {
        if (entry.lifetimeSeconds() <= 0) {
            return;
        }
        long expireAt = System.currentTimeMillis() + entry.lifetimeSeconds() * 1000L;
        if (expireAt <= entry.expireAtMillis()) {
            return;
        }
        tracked.put(entry.key(), entry.withExpiry(expireAt));
        enqueueExpiry(entry.key(), expireAt);
    }

    /**
     * 区块标识：世界 UUID + 区块坐标，用于把同一区块的到期方块合并成一次区域任务。
     *
     * <p>旧实现把三者塞进一个 long（{@code worldHash<<40 ^ cx<<20 ^ cz}）：世界哈希只留 24 位、
     * {@code cx<<20} 与它重叠、{@code cz} 又与 {@code cx<<20} 重叠，于是
     * {@code (cx=1, cz=1048576)} 和 {@code (cx=0, cz=0)} 会算出同一个 key——两个不同区块的方块
     * 被并成一批，任务投到第一个区块的区域线程里，去改另一个区块的方块，直接炸掉整批。
     * 用 record 当 Map key 就没有这个问题。</p>
     */
    private static ChunkRef chunkKey(TrackedBlock entry) {
        return new ChunkRef(entry.worldId(), entry.x() >> 4, entry.z() >> 4);
    }

    /**
     * 登记一个怪物放置的方块。
     *
     * @param pos            位置
     * @param data           放置时的方块数据
     * @param lifetimeSeconds 存在时间（秒），&lt;= 0 表示永久（只做来源标记）
     */
    public void track(BlockPos pos, BlockData data, int lifetimeSeconds) {
        Material material = data.getMaterial();
        long expireAt = lifetimeSeconds > 0
                ? System.currentTimeMillis() + Math.max(1L, lifetimeSeconds) * 1000L
                : 0L;
        TrackedBlock entry = new TrackedBlock(pos.world().getUID(), pos.x(), pos.y(), pos.z(),
                material, expireAt, Math.max(0, lifetimeSeconds));
        tracked.put(entry.key(), entry);
        insertionOrder.add(entry.key());
        pruneIfNeeded();
        if (expireAt > 0L) {
            // 交给全局清扫器（比一次性延迟任务可靠：区域暂停 / 区块未加载都不会丢）。
            enqueueExpiry(entry.key(), expireAt);
        }
    }

    /**
     * 该方块是否是怪物放下的（用于避免怪物互拆）。
     *
     * <p>如果登记的类型与当前方块不一致（被玩家换掉了），顺手清掉这条记录。</p>
     */
    public boolean isPlacedByMob(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        String key = makeKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        TrackedBlock entry = tracked.get(key);
        if (entry == null) {
            return false;
        }
        Material current;
        try {
            current = block.getType();
        } catch (Throwable throwable) {
            // 极端情况下（区块刚被卸载 / 区域迁移）读方块可能失败，按“不是怪物放的”处理。
            return false;
        }
        if (entry.material() != current) {
            remove(key);
            return false;
        }
        return true;
    }

    /**
     * 删除一个到期方块（区域线程内执行）。
     *
     * <p>只有在“确实处理过”之后才把记录移除：区块没加载 / 读不到方块时保留记录，
     * 交给清扫器下次重试——旧实现先删记录再判断，区块恰好没加载就会把记录丢掉、方块永远留下。</p>
     */
    private void expire(World world, TrackedBlock entry) {
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            retryLater(entry);
            return;
        }
        Block block;
        Material current;
        try {
            block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            current = block.getType();
        } catch (Throwable throwable) {
            retryLater(entry);
            return;
        }
        if (current != entry.material()) {
            // 玩家换过方块：不再归我们管，丢掉记录即可。
            remove(entry.key());
            return;
        }
        block.setBlockData(Material.AIR.createBlockData(), true);
        remove(entry.key());
    }

    /** 这次删不了（区块没加载 / 读失败）：把这条重新排进到期表，别让记录变成孤儿。 */
    private void retryLater(TrackedBlock entry) {
        tracked.put(entry.key(), entry);
        enqueueExpiry(entry.key(), System.currentTimeMillis() + RETRY_DELAY_MILLIS);
    }

    /**
     * 插件卸载 / 服务器关闭时，把怪物放下的方块全部删掉。
     *
     * <p>两条路都走一遍，谁先成功算谁：</p>
     * <ol>
     *   <li><b>直接删</b>：{@code onDisable} 时区域调度器往往已经拒绝新任务（旧版本就是因此
     *       什么都没删），所以在当前线程直接改方块；</li>
     *   <li><b>投递区域任务</b>：Folia 会对“非本区域线程改方块”抛异常，这时退回把删除投递到
     *       该方块所在区域（区域还活着就能执行）。</li>
     * </ol>
     *
     * <p>两条路都会在删除前再次比对材质，玩家后来换上去的方块不会误删。
     * 每个方块单独 try/catch，删不掉只记日志，绝不影响关服。</p>
     *
     * @return 实际删掉的方块数量
     */
    public int removeAllNow() {
        int removed = 0;
        int scheduled = 0;
        for (TrackedBlock entry : tracked.values()) {
            World world = plugin.getServer().getWorld(entry.worldId());
            if (world == null) {
                continue;
            }
            if (removeNow(world, entry)) {
                removed++;
                continue;
            }
            if (scheduleRemove(world, entry)) {
                scheduled++;
            }
        }
        int total = tracked.size();
        tracked.clear();
        insertionOrder.clear();
        if (scheduled > 0) {
            plugin.getLogger().info("卸载清理：直接删除 " + removed + " 个，另投递区域任务 "
                    + scheduled + " 个（共登记 " + total + " 个）");
        }
        return removed;
    }

    /** 同步删除单个登记方块；类型已被换掉 / 区块未加载 / 线程不对时返回 {@code false}。 */
    private boolean removeNow(World world, TrackedBlock entry) {
        try {
            if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
                return false;
            }
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (block.getType() != entry.material()) {
                return false;
            }
            block.setBlockData(Material.AIR.createBlockData(), false);
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().log(java.util.logging.Level.FINE,
                    "卸载时直接删除方块失败，改投递区域任务 @ "
                            + entry.x() + "," + entry.y() + "," + entry.z(),
                    throwable);
            return false;
        }
    }

    /** 兜底：把删除投递到该方块所在区域（Folia 上区域还活着时才会执行）。 */
    private boolean scheduleRemove(World world, TrackedBlock entry) {
        if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            return false;
        }
        return plugin.schedulers().region(world, entry.x(), entry.z(), () -> expire(world, entry));
    }

    public int size() {
        return tracked.size();
    }

    /**
     * 把“当前还登记着的方块”写到 {@code pending-blocks.txt}。
     *
     * <p>在卸载前调用：万一本进程在关服过程中被强杀、或者 Folia 的区域线程已经不再执行任务，
     * 下次启动时 {@link #loadJournalAndClean()} 会再清一遍。</p>
     */
    public void saveJournal() {
        File file = new File(plugin.getDataFolder(), JOURNAL_FILE);
        try {
            if (tracked.isEmpty()) {
                deleteJournal(file);
                return;
            }
            List<String> lines = new ArrayList<>(tracked.size());
            for (TrackedBlock entry : tracked.values()) {
                lines.add(entry.toLine());
            }
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("写入待删除清单失败：" + exception.getMessage());
        }
    }

    /** 全部删干净后把清单删掉（只留真正没删掉的尾巴给下次启动兜底）。 */
    public void clearJournal() {
        deleteJournal(new File(plugin.getDataFolder(), JOURNAL_FILE));
    }

    private void deleteJournal(File file) {
        if (file.isFile() && !file.delete()) {
            plugin.getLogger().fine("清理待删除清单失败：" + file);
        }
    }

    /**
     * 启动兜底：把上次没删干净的方块再删一遍（已经不在的、被玩家换掉的都会跳过）。
     *
     * <p>区块还没加载的条目会留在清单里，等下次启动再试，不会丢。</p>
     */
    public void loadJournalAndClean() {
        File file = new File(plugin.getDataFolder(), JOURNAL_FILE);
        if (!file.isFile()) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("读取待删除清单失败：" + exception.getMessage());
            return;
        }
        if (lines.isEmpty()) {
            if (!file.delete()) {
                plugin.getLogger().fine("清理待删除清单失败：" + file);
            }
            return;
        }
        List<String> remaining = new ArrayList<>();
        int scheduled = 0;
        for (String line : lines) {
            TrackedBlock entry = parseJournalLine(line);
            if (entry == null) {
                continue;
            }
            World world = plugin.getServer().getWorld(entry.worldId());
            if (world == null) {
                // 世界这次没加载：留着，下次再试。
                remaining.add(line);
                continue;
            }
            if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
                remaining.add(line);
                continue;
            }
            if (scheduleRemove(world, entry)) {
                scheduled++;
            } else {
                remaining.add(line);
            }
        }
        plugin.getLogger().info("启动兜底清理：上次遗留的怪物方块 " + scheduled + " 个已安排删除，"
                + remaining.size() + " 个等区块加载后再删");
        try {
            if (remaining.isEmpty()) {
                if (!file.delete()) {
                    plugin.getLogger().fine("清理待删除清单失败：" + file);
                }
            } else {
                Files.write(file.toPath(), remaining, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("更新待删除清单失败：" + exception.getMessage());
        }
    }

    private TrackedBlock parseJournalLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 5) {
            return null;
        }
        try {
            UUID worldId = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Material material = Material.getMaterial(parts[4].toUpperCase(Locale.ROOT));
            if (material == null) {
                return null;
            }
            return new TrackedBlock(worldId, x, y, z, material, 0L, 0);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void remove(String key) {
        tracked.remove(key);
    }

    /**
     * 容量控制。
     *
     * <p>两个方向都要管：</p>
     * <ol>
     *   <li>登记表超过 {@link #MAX_TRACKED} 时淘汰最旧的记录（原有的“防永久累积”）；</li>
     *   <li><b>插入顺序队列本身</b>也要压缩。{@code remove()} 只删 map、不删队列，
     *       而正常运行时 map 是“放一个删一个”、永远到不了 5 万，所以旧实现的
     *       {@code poll()} 几乎从不发生——队列只增不减，24×7 跑下去就是内存曲线只涨不跌
     *       （每个 key 加节点约 100 多字节，一百万次放置就是上百 MB）。</li>
     * </ol>
     */
    private void pruneIfNeeded() {
        while (tracked.size() > MAX_TRACKED) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            tracked.remove(oldest);
        }
        if (insertionOrder.size() > MAX_TRACKED) {
            // 只是“曾经登记过的 key”太多了：把已经不在登记表里的清掉即可（弱一致遍历，安全）。
            insertionOrder.removeIf(key -> !tracked.containsKey(key));
        }
    }

    private static String makeKey(UUID worldId, int x, int y, int z) {
        return worldId + ":" + x + ":" + y + ":" + z;
    }
}
