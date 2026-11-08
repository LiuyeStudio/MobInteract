package studio.liuye.mobinteract.config;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * 一条“可放置条目”。
 *
 * @param id            唯一标识（同时用于命令展示）
 * @param type          条目类别
 * @param weight        相对权重（&gt; 0），背包抽取与放置抽取都使用它
 * @param material      方块 / 流体的 Material；TNT 条目固定为 TNT
 * @param blockData     可选的方块数据字符串（例如 {@code facing=north}），空串表示默认状态
 * @param fuseTicks     TNT 引信时长（tick）
 * @param fluid         流体种类；仅 FLUID 条目有值
 * @param consumeBucket 倾倒后是否扣除该条目；仅 FLUID 条目有意义
 */
public record PlacementEntry(
        String id,
        EntryType type,
        double weight,
        Material material,
        String blockData,
        int fuseTicks,
        FluidKind fluid,
        boolean consumeBucket) {

    /** 该条目放置时使用的方块数据；解析失败时退回默认状态。 */
    public BlockData resolveBlockData() {
        Material target = material;
        if (target == null || target.isAir()) {
            throw new IllegalStateException("条目 " + id + " 没有可用的方块类型");
        }
        if (blockData == null || blockData.isBlank()) {
            return target.createBlockData();
        }
        return target.createBlockData(blockData);
    }

    /** TNT / 水桶 / 熔岩桶这类“功能方块”：默认只用于攻击目标，而不是用来搭桥 / 垫高。 */
    public boolean isSpecial() {
        return type == EntryType.TNT || type == EntryType.FLUID;
    }

    /** 放置后是否需要登记为“临时方块”（到期移除）。 */
    public boolean isPersistentBlock() {
        return type == EntryType.BLOCK || type == EntryType.FLUID;
    }
}
