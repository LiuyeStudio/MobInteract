package studio.liuye.mobinteract.placement;

import org.bukkit.Material;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.backpack.MobBackpack;
import studio.liuye.mobinteract.config.EntryType;
import studio.liuye.mobinteract.config.FluidKind;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.mob.MobProfile;

/**
 * 怪物“手持方块”的表现层。
 *
 * <p>有方块背包的怪物会一直把当前要用的方块拿在<b>副手</b>上（主手留给镐子），
 * 物品数量与背包库存一致，放置一次就少一个；背包空了就清空副手。
 * 这样既满足“手上一定有方块”，也让玩家能直观看出剩余数量。</p>
 *
 * <p>遵守两条边界：</p>
 * <ul>
 *   <li>怪物原本副手就有物品（盾牌等）时不覆盖；</li>
 *   <li>放上去的物品掉落概率设 0，避免击杀刷物品。</li>
 * </ul>
 */
public final class HeldItemService {

    private final MobInteractPlugin plugin;

    public HeldItemService(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /** 同步怪物手上的方块（可在任意实体线程调用；必要时会自动切线程外的调用方负责）。 */
    public void sync(Mob mob) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return;
        }
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) {
            return;
        }
        MobProfile profile = plugin.registry().profile(mob);
        MobBackpack backpack = profile.backpack();

        if (backpack == null || backpack.isEmpty()) {
            if (profile.handManaged()) {
                equipment.setItemInOffHand(null);
                profile.setHandManaged(false);
            }
            profile.setHeldEntry(null);
            return;
        }

        // 尊重原版已有的副手物品。
        ItemStack current = equipment.getItemInOffHand();
        if (!profile.handManaged() && current != null && !current.getType().isAir()) {
            return;
        }

        PlacementEntry entry = profile.heldEntry();
        if (entry == null || backpack.amountOf(entry) <= 0) {
            entry = backpack.pick(plugin.random());
            profile.setHeldEntry(entry);
        }
        if (entry == null) {
            return;
        }
        int amount = Math.max(1, Math.min(64, backpack.amountOf(entry)));
        Material material = displayMaterial(entry);
        if (material == null) {
            return;
        }
        // 已经是同样的物品和数量就不重复发包。
        if (current != null && current.getType() == material && current.getAmount() == amount) {
            profile.setHandManaged(true);
            return;
        }
        ItemStack stack = new ItemStack(material, amount);
        equipment.setItemInOffHand(stack);
        equipment.setDropChance(EquipmentSlot.OFF_HAND, 0.0F);
        profile.setHandManaged(true);
    }

    /** 条目对应的展示物品。 */
    private Material displayMaterial(PlacementEntry entry) {
        if (entry.type() == EntryType.BLOCK) {
            return entry.material();
        }
        if (entry.type() == EntryType.TNT) {
            return Material.TNT;
        }
        FluidKind fluid = entry.fluid();
        if (fluid == FluidKind.LAVA) {
            return Material.LAVA_BUCKET;
        }
        if (fluid == FluidKind.WATER) {
            return Material.WATER_BUCKET;
        }
        return null;
    }
}
