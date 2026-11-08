package studio.liuye.mobinteract.safety;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.generator.WorldInfo;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.config.PlacementSettings;

/**
 * 方块修改前的公共安全检查。
 *
 * <p>所有方法都只读取方块本身，调用方必须已经处于该方块所在区域的线程上。</p>
 */
public final class BlockSafety {

    /** 无论如何都不允许挖掘的方块（不可破坏方块 + 强功能方块）。 */
    private static final Set<Material> HARD_BLACKLIST = EnumSet.of(
            Material.BEDROCK,
            Material.BARRIER,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.COMMAND_BLOCK_MINECART,
            Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID,
            Material.JIGSAW,
            Material.END_PORTAL,
            Material.END_PORTAL_FRAME,
            Material.END_GATEWAY,
            Material.NETHER_PORTAL,
            Material.MOVING_PISTON,
            Material.LIGHT,
            Material.SPAWNER,
            Material.BUDDING_AMETHYST);

    private BlockSafety() {
    }

    /** 该材料是否属于“无论如何都不该被本插件动”的不可破坏 / 强功能方块。 */
    public static boolean isForbidden(Material material) {
        return material == null || HARD_BLACKLIST.contains(material);
    }

    /** 方块能否被本插件挖掉。 */
    public static boolean canBreak(Block block, MiningSettings settings) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type.isAir() || block.isLiquid()) {
            return false;
        }
        if (HARD_BLACKLIST.contains(type)) {
            return false;
        }
        if (!settings.allowsMaterial(type)) {
            return false;
        }
        if (type.getHardness() < 0.0F) {
            return false;
        }
        if (!insideWorld(block) || !insideBorder(block)) {
            return false;
        }
        if (settings.disallowTileEntities() && block.getState() instanceof TileState) {
            return false;
        }
        return true;
    }

    /** 该位置能否被本插件放置方块。 */
    public static boolean canPlaceAt(Block block, PlacementSettings settings) {
        if (block == null) {
            return false;
        }
        if (!block.isReplaceable() || block.isLiquid()) {
            return false;
        }
        if (!insideWorld(block) || !insideBorder(block)) {
            return false;
        }
        World world = block.getWorld();
        return world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    /** 坐标是否在世界高度范围内。 */
    public static boolean insideWorld(Block block) {
        World world = block.getWorld();
        WorldInfo info = world;
        int y = block.getY();
        return y >= info.getMinHeight() && y < info.getMaxHeight();
    }

    /** 坐标是否在世界边界内。 */
    public static boolean insideBorder(Block block) {
        World world = block.getWorld();
        return world.getWorldBorder().isInside(block.getLocation().add(0.5D, 0.5D, 0.5D));
    }
}
