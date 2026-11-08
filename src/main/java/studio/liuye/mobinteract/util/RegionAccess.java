package studio.liuye.mobinteract.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 跨区域（Folia）安全的方块读取。
 *
 * <p>Folia 下访问不属于当前区域线程的方块会抛 {@code IllegalStateException}（“Accessing
 * block outside of region”），而且是<b>每次调用都抛</b>：一段每 tick 执行的规划代码只要碰到
 * 一个越界的坐标，控制台就会被同一个堆栈刷满，服主看到的是“插件在刷错误”，实际只是
 * 目标方块刚好在隔壁区域。</p>
 *
 * <p>这里把三件事一次做完，调用方不必各自复制：</p>
 * <ol>
 *   <li>区块没加载就直接放弃（{@code getBlockAt} 会强制加载区块，那是很贵的副作用）；</li>
 *   <li>不在当前区域就放弃（{@link Bukkit#isOwnedByCurrentRegion} 只做坐标判断，不读世界）；</li>
 *   <li>真正读取时仍然包一层 try/catch —— 区域可能在两次判断之间被迁移走。</li>
 * </ol>
 *
 * <p>失败一律返回 {@code false}，也就是“当作这一格不能用”：规划器会退回“不放置 / 换一列”，
 * 比让怪物在越界坐标上乱放方块安全得多。</p>
 */
public final class RegionAccess {

    private RegionAccess() {
    }

    /** 当前线程是否拥有这个区块（区块未加载时也返回 {@code false}）。 */
    public static boolean ownChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return false;
        }
        try {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return false;
            }
            // 用方块中心坐标问“这一块归不归我”：只做坐标计算，不读世界数据，跨区域也能安全调用。
            return Bukkit.isOwnedByCurrentRegion(
                    new org.bukkit.Location(world, (chunkX << 4) + 8, world.getMinHeight(), (chunkZ << 4) + 8));
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 当前线程是否拥有这个方块所在的区块。 */
    public static boolean own(BlockPos pos) {
        return pos != null && ownChunk(pos.world(), pos.chunkX(), pos.chunkZ());
    }

    /**
     * 安全地判断这一格是不是“可以放东西的空位”。
     *
     * @return 只有在“本线程拥有该区块 + 区块已加载 + 读取成功”时才返回真实结果，否则 {@code false}
     */
    public static boolean isReplaceable(BlockPos pos) {
        if (!own(pos)) {
            return false;
        }
        try {
            return pos.block().isReplaceable();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 在拥有该区块的前提下取出方块；不满足条件或读取失败时返回 {@code null}。 */
    public static Block block(BlockPos pos) {
        if (!own(pos)) {
            return null;
        }
        try {
            return pos.block();
        } catch (Throwable throwable) {
            return null;
        }
    }
}
