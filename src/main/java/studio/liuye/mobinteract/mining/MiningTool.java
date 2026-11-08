package studio.liuye.mobinteract.mining;

import org.bukkit.Material;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import studio.liuye.mobinteract.config.MiningSettings;

/**
 * 怪物手持工具（镐子）相关的判定。
 *
 * <p>挖掘速度以“木镐”为基准：配置里的 {@code break-time.multiplier} 就是木镐的速度，
 * 其他材质按 {@code tool.speed} 里的系数加速（系数越大越快）。</p>
 */
public final class MiningTool {

    /** 空手且未要求持镐时返回的速度系数标记。 */
    public static final double NOT_ALLOWED = -1.0D;

    private MiningTool() {
    }

    /** 是否是一把镐子（按 Material 名称判定，兼容各版本新增材质）。 */
    public static boolean isPickaxe(Material material) {
        return material != null && material.isItem() && material.name().endsWith("_PICKAXE");
    }

    /** 主手持有的镐子材质；没有镐子时返回 {@code null}。 */
    public static Material heldPickaxe(Mob mob) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) {
            return null;
        }
        ItemStack item = equipment.getItemInMainHand();
        if (item == null) {
            return null;
        }
        Material type = item.getType();
        return isPickaxe(type) ? type : null;
    }

    /**
     * 该怪物当前的挖掘速度系数（相对木镐）。
     *
     * @return 速度系数；{@code require-pickaxe} 为真且没有镐子时返回 {@link #NOT_ALLOWED}
     */
    public static double speedFactor(Mob mob, MiningSettings settings) {
        Material pickaxe = heldPickaxe(mob);
        if (pickaxe == null) {
            if (settings.requirePickaxe()) {
                return NOT_ALLOWED;
            }
            return settings.speedFor(null);
        }
        return settings.speedFor(pickaxe);
    }
}
