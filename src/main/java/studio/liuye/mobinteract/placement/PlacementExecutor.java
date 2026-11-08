package studio.liuye.mobinteract.placement;

import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.api.MobBlockChangeEvent;
import studio.liuye.mobinteract.config.EntryType;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.safety.BlockSafety;
import studio.liuye.mobinteract.util.BlockPos;
import studio.liuye.mobinteract.util.RegionAccess;

/**
 * 真正执行“放置”的一步。
 *
 * <p>线程模型：调用方处于怪物所属区域线程；本类先捕获目标位置，
 * 再把方块修改投递到<b>目标位置所在区域</b>的线程，完成后再切回实体调度器回调结果，
 * 不在实体线程里直接改可能属于别的 region 的方块。</p>
 */
public final class PlacementExecutor {

    private final MobInteractPlugin plugin;

    /** TNT 的波及半径（格）。原版爆炸半径约 4 格，这里按 4 格保守检查。 */
    private static final int TNT_CHECK_RADIUS = 4;

    /** 流体只检查一格邻域：水 / 岩浆落地后最多先流一格。 */
    private static final int FLUID_CHECK_RADIUS = 1;

    public PlacementExecutor(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param mob      发起放置的怪物
     * @param entry    选中的条目
     * @param target   放置位置与方式
     * @param settings 配置快照
     * @param callback 结果回调（true = 放置成功），在怪物所属区域线程上执行
     */
    public void execute(Mob mob, PlacementEntry entry, PlacementTarget target, PlacementSettings settings,
            Consumer<Boolean> callback) {
        if (mob == null || entry == null || target == null || callback == null) {
            return;
        }
        if (target.isRelocate()) {
            // “走到垫高列”只是移动指令，不需要放置方块；被误传进来时直接按失败结算。
            callback.accept(false);
            return;
        }
        BlockPos pos = target.pos();
        if (!target.isPillarUp()) {
            place(mob, entry, pos, settings, callback);
            return;
        }
        // 竖直垫高：Folia 下 Entity#teleport 会抛 UnsupportedOperationException（必须用 teleportAsync），
        // 所以先异步抬高，成功后回到实体线程再执行放置。
        Location up = mob.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        mob.teleportAsync(up).thenAccept(lifted -> plugin.schedulers().entity(mob, () -> {
            if (!Boolean.TRUE.equals(lifted) || !mob.isValid()) {
                callback.accept(false);
                return;
            }
            mob.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            place(mob, entry, pos, settings, callback);
        }));
    }

    /** 把方块放到目标位置：区域线程执行，结果回到实体线程。 */
    private void place(Mob mob, PlacementEntry entry, BlockPos pos, PlacementSettings settings,
            Consumer<Boolean> callback) {
        boolean queued = plugin.schedulers().runAtBlock(pos.world(), pos.x(), pos.y(), pos.z(), () -> {
            boolean success = false;
            try {
                success = apply(mob, entry, pos, settings);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "怪物放置方块时出错（" + entry.id() + "）", throwable);
            }
            boolean result = success;
            plugin.schedulers().entity(mob, () -> callback.accept(result));
        });
        if (!queued) {
            // 目标位置当前不可操作（区块未加载 / 世界关闭 / 坐标越界）：直接按失败处理，
            // 由调用方进入重试冷却，避免每分钟刷一次调度异常。
            plugin.schedulers().entity(mob, () -> callback.accept(false));
        }
    }

    /** 必须在目标位置所属区域的线程上执行。 */
    private boolean apply(Mob mob, PlacementEntry entry, BlockPos pos, PlacementSettings settings) {
        World world = pos.world();
        if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            return false;
        }
        Block block = pos.block();
        if (!BlockSafety.canPlaceAt(block, settings)) {
            return false;
        }
        if (occupied(world, pos, mob)) {
            // 规划时说好是空位、真到执行时却有人站进去了（玩家追怪跑过、别的怪挤过来）。
            // 从“决定放”到“真的放”至少要跨好几个 tick，这中间世界会变，所以必须在写入前复核一次。
            return false;
        }
        if (!plugin.canChange(block, mob, MobBlockChangeEvent.Action.PLACE)) {
            return false;
        }

        switch (entry.type()) {
            case BLOCK -> {
                BlockData data;
                try {
                    data = entry.resolveBlockData();
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("条目 " + entry.id() + " 的 block-data 无法解析："
                            + exception.getMessage());
                    return false;
                }
                block.setBlockData(data, true);
                world.playSound(pos.center(), Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.8F, 0.9F);
                world.spawnParticle(Particle.BLOCK, pos.center(), 8, 0.3D, 0.3D, 0.3D, data);
                plugin.tempBlocks().track(pos, data, settings.blockLifetimeSeconds());
                return true;
            }
            case FLUID -> {
                if (neighboursProtected(mob, pos, FLUID_CHECK_RADIUS)) {
                    // 水 / 岩浆落地后会往四周流一格：只校验落点，等于给保护区开了个后门。
                    return false;
                }
                BlockData data;
                try {
                    data = entry.resolveBlockData();
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("条目 " + entry.id() + " 的流体无法创建："
                            + exception.getMessage());
                    return false;
                }
                block.setBlockData(data, true);
                world.playSound(pos.center(), Sound.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
                plugin.tempBlocks().track(pos, data, settings.blockLifetimeSeconds());
                return true;
            }
            case TNT -> {
                if (neighboursProtected(mob, pos, TNT_CHECK_RADIUS)) {
                    // 爆炸会波及半径内的方块：落点本身可能就在保护区外，只查落点等于放行。
                    // 宁可这次不炸，也不替服主拆掉保护区边缘的建筑。
                    return false;
                }
                // 规格要求：不要“先放 TNT 方块再点燃”，直接生成已点燃的 TNT 实体。
                if (block.getType() != Material.AIR && block.isReplaceable()) {
                    // 目标位置若残留可替换方块（草、雪等），先清掉，避免出现“方块 + 实体”中间态。
                    block.setBlockData(Material.AIR.createBlockData(), false);
                }
                Location spawnLocation = pos.center();
                world.playSound(spawnLocation, Sound.ENTITY_TNT_PRIMED, SoundCategory.BLOCKS, 1.0F, 1.0F);
                TNTPrimed tnt = world.spawn(spawnLocation, TNTPrimed.class, entity -> {
                    entity.setSource(mob);
                    entity.setFuseTicks(entry.fuseTicks());
                }, CreatureSpawnEvent.SpawnReason.CUSTOM);
                if (tnt == null) {
                    return false;
                }
                // 引信时长最终在 TNT 自己的实体线程上设置一次，避免跨线程写实体状态。
                plugin.schedulers().entity(tnt, () -> {
                    if (tnt.isValid()) {
                        tnt.setFuseTicks(entry.fuseTicks());
                    }
                });
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 这一格是否已经被“活的东西”占了。
     *
     * <p>方块是 1×1×1，实体却是浮点包围盒，所以取方块内部收窄一点的范围去查：
     * 只碰到方块边角的生物不算占用（否则贴着墙走的玩家会把整条路堵死），
     * 真正站在方块中央的才算。</p>
     *
     * <p>查询失败按<b>占用</b>处理：这一轮不放，最多是怪物少铺一块；
     * 反过来就是往玩家身上塞方块，人会被卡进墙里。</p>
     */
    private boolean occupied(World world, BlockPos pos, Mob mob) {
        BoundingBox box = new BoundingBox(
                pos.x() + 0.15D, pos.y() + 0.05D, pos.z() + 0.15D,
                pos.x() + 0.85D, pos.y() + 0.95D, pos.z() + 0.85D);
        try {
            for (Entity entity : world.getNearbyEntities(box)) {
                if (entity == mob || !entity.isValid() || entity.isDead()) {
                    continue;
                }
                if (entity instanceof LivingEntity) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            return true;
        }
        return false;
    }

    /**
     * 落点周围的方块里有没有被保护插件拦着的。
     *
     * <p>{@code canChange} 只校验落点本身，可爆炸和流体都会波及邻格：在保护区外一格倒水、
     * 或贴着保护区边缘点 TNT，照样能改到保护区里的方块。这里按半径粗查一遍，
     * 命中任意一格就放弃这次放置（宁可这次不放，也不替服主拆家）。</p>
     */
    private boolean neighboursProtected(Mob mob, BlockPos pos, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (!RegionAccess.own(candidate)) {
                        // 邻格属于别的区域：这一轮不判它，交给它自己的区域线程处理。
                        continue;
                    }
                    Block block = candidate.block();
                    if (block.getType() == Material.AIR) {
                        continue;
                    }
                    if (!plugin.canChange(block, mob, MobBlockChangeEvent.Action.PLACE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 该条目放置成功后是否要从背包扣除（桶可以配置为不消耗）。 */
    public static boolean consumesOnSuccess(PlacementEntry entry) {
        if (entry.type() == EntryType.FLUID) {
            return entry.consumeBucket();
        }
        return true;
    }
}
