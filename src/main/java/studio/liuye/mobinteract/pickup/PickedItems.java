package studio.liuye.mobinteract.pickup;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import studio.liuye.mobinteract.config.EntryType;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.safety.BlockSafety;

/**
 * “捡到的物品怎么用”的纯逻辑：判断能不能捡、捡到的方块变成哪个背包条目。
 *
 * <p>不触碰世界与实体，便于单元测试；真正捡起来的过程见 {@link ItemPickupService}。</p>
 */
public final class PickedItems {

    /**
     * 捡到的方块在背包里的权重。
     *
     * <p>与默认配置里常见条目（圆石 30 / 泥土 22 / 石头 18 …）同一量级，
     * 这样“捡来的方块”也有机会被抽中，不会永远排在配置条目后面。</p>
     */
    public static final double PICKED_WEIGHT = 10.0D;

    /** 捡来方块的条目 id 前缀（与配置里的条目区分开，方便 /mobinteract inspect 辨认）。 */
    public static final String PICKED_ID_PREFIX = "picked_";

    private PickedItems() {
    }

    /** 是不是镐子（材质名以 {@code _PICKAXE} 结尾）。 */
    public static boolean isPickaxe(Material material) {
        return material != null && material.name().endsWith("_PICKAXE");
    }

    /**
     * 捡到的方块能不能放进方块背包：必须是可放置的方块物品，且不是强功能 / 不可破坏方块，
     * 也**不是受重力影响的方块**（沙子 / 沙砾这类放下去会塌，搭不了桥和柱子）。
     *
     * <p>实现依赖 {@code Material#isBlock()} / {@code isItem()} / {@code hasGravity()}，
     * 这些方法需要初始化服务端的方块注册表，单元测试里跑不了，所以这一条在真实服务端上验证
     * （见 README 的运行验证一节）。</p>
     */
    public static boolean canStore(Material material) {
        return material != null
                && !material.isAir()
                && material.isBlock()
                && material.isItem()
                && !material.hasGravity()
                && !BlockSafety.isForbidden(material);
    }

    /**
     * 为某个材质找背包条目。
     *
     * <p>配置里本来就有同材质条目时直接复用（保留原来的 id 与权重）；
     * 否则临时造一条“捡来的”条目。同一个材质每次造出的条目内容一致，
     * 所以背包会把它累加到同一条上，不会长出重复条目。</p>
     *
     * @param entries  配置里的条目目录，可为 {@code null}
     * @param material 捡到的材质
     * @return 背包条目
     */
    public static PlacementEntry entryFor(List<PlacementEntry> entries, Material material) {
        if (entries != null) {
            for (PlacementEntry entry : entries) {
                if (entry.type() == EntryType.BLOCK && entry.material() == material) {
                    return entry;
                }
            }
        }
        return new PlacementEntry(PICKED_ID_PREFIX + material.name().toLowerCase(Locale.ROOT),
                EntryType.BLOCK, PICKED_WEIGHT, material, "", 0, null, true);
    }
}
