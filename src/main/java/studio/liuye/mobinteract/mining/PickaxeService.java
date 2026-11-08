package studio.liuye.mobinteract.mining;

import java.util.Map;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.backpack.WeightedSampler;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.mob.MobProfile;

/**
 * 让合适的人形怪物“自带镐子”。
 *
 * <p>只有持镐的怪物才会挖掘（{@code tool.require-pickaxe} 默认开启），
 * 所以插件需要在怪物进入世界时按概率发一把镐子；材质按权重抽取，
 * 并据此决定挖掘速度。</p>
 *
 * <p>发放的镐子默认<b>不会掉落</b>（{@code tool.give.drop-chance} 默认 0），
 * 避免玩家靠击杀怪物刷装备。怪物原本就持有的物品不会被覆盖，
 * 也不会改变它的掉落概率。</p>
 */
public final class PickaxeService {

    private final MobInteractPlugin plugin;

    public PickaxeService(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /** 必须在怪物所属区域的线程上调用；每只怪物只抽一次签。 */
    public void maybeEquip(Mob mob) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return;
        }
        MiningSettings settings = plugin.settings().mining();
        if (!settings.givePickaxe() || !settings.mobFilter().allows(mob)) {
            return;
        }
        MobProfile profile = plugin.registry().profile(mob);
        if (profile.pickaxeChecked()) {
            return;
        }
        profile.markPickaxeChecked();

        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) {
            return;
        }
        ItemStack current = equipment.getItemInMainHand();
        if (current != null && !current.getType().isAir()) {
            // 尊重已有的手持物品（可能是原版自带的或捡到的镐子）。
            return;
        }
        Random random = plugin.random();
        if (random.nextDouble() >= settings.givePickaxeChance()) {
            return;
        }
        Material material = pickMaterial(settings.pickaxeWeights(), random);
        if (material == null) {
            return;
        }
        equipment.setItemInMainHand(new ItemStack(material));
        // 掉落概率交给原版装备掉落逻辑；默认 0 表示不掉落。
        equipment.setDropChance(EquipmentSlot.HAND, (float) settings.pickaxeDropChance());
        profile.markPickaxeGranted();
    }

    private Material pickMaterial(Map<Material, Integer> weights, Random random) {
        if (weights.isEmpty()) {
            return null;
        }
        return WeightedSampler.pick(new java.util.ArrayList<>(weights.keySet()),
                material -> weights.getOrDefault(material, 0), random);
    }
}
