package studio.liuye.mobinteract.mining;

import org.bukkit.Material;
import studio.liuye.mobinteract.util.BlockPos;

/**
 * 一次挖掘的目标。
 *
 * @param pos        目标方块坐标
 * @param material   目标方块类型（启动时记录，执行期间用于校验）
 * @param seconds    计算出的怪物挖掘耗时（秒）
 * @param totalTicks 换算后的总 tick 数
 */
public record MiningCandidate(BlockPos pos, Material material, double seconds, int totalTicks) {
}
