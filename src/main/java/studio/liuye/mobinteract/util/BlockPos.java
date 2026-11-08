package studio.liuye.mobinteract.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 一个方块坐标值对象。
 *
 * <p>Folia 下跨调度器传递方块位置时，只传纯数据（世界引用 + 三个整数），
 * 真正访问方块一律在目标位置的区域线程里通过 {@link #block()} 重新获取，
 * 避免把 {@link Block} 实例带到别的线程。</p>
 */
public record BlockPos(World world, int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(world, x + dx, y + dy, z + dz);
    }

    /** 方块最小角的 Location（用于方块相关的判定 / 粒子）。 */
    public Location location() {
        return new Location(world, x, y, z);
    }

    /** 方块中心的 Location（用于距离、朝向、粒子与爆炸判定）。 */
    public Location center() {
        return new Location(world, x + 0.5D, y + 0.5D, z + 0.5D);
    }

    /** 必须在拥有该方块区域的线程中调用。 */
    public Block block() {
        return world.getBlockAt(x, y, z);
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public double distanceTo(Location location) {
        double dx = (x + 0.5D) - location.getX();
        double dy = (y + 0.5D) - location.getY();
        double dz = (z + 0.5D) - location.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean sameWorld(World other) {
        return world.equals(other);
    }
}
