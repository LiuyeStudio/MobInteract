package studio.liuye.mobinteract.placement;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.BoundingBox;

/**
 * {@link BuildWorld} 的 Bukkit 实现。
 *
 * <p>只做读取，并且尊重 Folia 的区域边界：不属于当前区域的方块一律当作“读不到”，
 * 宁可这一轮少搭一格，也不跨区域读方块。区块归属判断按区块缓存，一次扫描只查一次。</p>
 *
 * <p>必须在怪物所属区域的线程上构造与使用（与放置规划一致）。</p>
 */
final class BukkitBuildWorld implements BuildWorld {

    private final World world;
    private final Mob self;
    private final Entity chaseTarget;
    private final Map<Long, Boolean> ownedChunks = new HashMap<>();

    BukkitBuildWorld(World world, Mob self) {
        this.world = world;
        this.self = self;
        this.chaseTarget = self == null ? null : self.getTarget();
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        if (world == null) {
            return false;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        return ownedByCurrentRegion(x, z);
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        return block(x, y, z).isPassable();
    }

    @Override
    public boolean isReplaceable(int x, int y, int z) {
        return block(x, y, z).isReplaceable();
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return block(x, y, z).isLiquid();
    }

    @Override
    public boolean hasEntity(int x, int yFrom, int yTo, int z) {
        return hasEntityIn(x, yFrom, yTo, z, false);
    }

    @Override
    public boolean hasAnyEntity(int x, int yFrom, int yTo, int z) {
        return hasEntityIn(x, yFrom, yTo, z, true);
    }

    /**
     * @param includeTarget 是否把“追击目标”也算成占位。
     *                      <p>放方块进任何生物身体里都会挤压，所以“方块落点”用 {@code true}；
     *                      而“走过去 / 从这一列垫上去”不该被目标挡住（否则只差最后一格就停了），用 {@code false}。</p>
     */
    private boolean hasEntityIn(int x, int yFrom, int yTo, int z, boolean includeTarget) {
        if (world == null || yTo < yFrom) {
            return false;
        }
        BoundingBox box = new BoundingBox(x + 0.05D, yFrom + 0.05D, z + 0.05D,
                x + 0.95D, yTo + 0.95D, z + 0.95D);
        try {
            for (Entity entity : world.getNearbyEntities(box)) {
                if (entity == self || !entity.isValid() || entity.isDead()) {
                    continue;
                }
                if (!includeTarget && entity == chaseTarget) {
                    continue;
                }
                if (entity instanceof LivingEntity) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            // 区域迁移 / 区块卸载期间实体查询可能失败。这里<b>按“有生物”处理</b>：
            // 放不下方块最多是怪物这一轮没铺成路，反过来（当成空位）就会把方块塞进别人身体里，
            // 玩家会被卡进墙、被活埋——两种代价完全不对等。
            return true;
        }
        return false;
    }

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    /** 当前线程是否拥有该方块所在区域（按区块缓存），判断不了时按“可以读”处理。 */
    private boolean ownedByCurrentRegion(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        Boolean cached = ownedChunks.get(key);
        if (cached != null) {
            return cached;
        }
        boolean owned;
        try {
            owned = Bukkit.isOwnedByCurrentRegion(
                    new Location(world, (chunkX << 4) + 8, world.getMinHeight(), (chunkZ << 4) + 8));
        } catch (Throwable throwable) {
            // 判断不了就当作“不是我的区域”：宁可这一轮不规划，也不要在别人的区域里读方块刷堆栈。
            owned = false;
        }
        ownedChunks.put(key, owned);
        return owned;
    }
}
