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
import org.bukkit.entity.Mob;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Vector;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.api.MobBlockChangeEvent;
import studio.liuye.mobinteract.config.EntryType;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.safety.BlockSafety;
import studio.liuye.mobinteract.util.BlockPos;

/**
 * 真正执行“放置”的一步。
 *
 * <p>线程模型：调用方处于怪物所属区域线程；本类先捕获目标位置，
 * 再把方块修改投递到<b>目标位置所在区域</b>的线程，完成后再切回实体调度器回调结果，
 * 不在实体线程里直接改可能属于别的 region 的方块。</p>
 */
public final class PlacementExecutor {

    private final MobInteractPlugin plugin;

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

    /** 该条目放置成功后是否要从背包扣除（桶可以配置为不消耗）。 */
    public static boolean consumesOnSuccess(PlacementEntry entry) {
        if (entry.type() == EntryType.FLUID) {
            return entry.consumeBucket();
        }
        return true;
    }
}
