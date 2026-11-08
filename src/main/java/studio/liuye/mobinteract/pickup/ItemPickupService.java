package studio.liuye.mobinteract.pickup;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.mob.MobProfile;

/**
 * 怪物主动拾取地上的物品。
 *
 * <p>做三件事：</p>
 * <ol>
 *   <li><b>找</b>：在 {@code pickup.radius} 内找“对得起这一趟”的掉落物——
 *       镐子（主手空着时）或者能放进方块背包的方块；</li>
 *   <li><b>走</b>：不在手边就交给原版寻路走过去（不覆盖挖掘 / 放置中的怪物）；</li>
 *   <li><b>捡</b>：进入 {@code pickup.reach} 后移除掉落物实体，并把内容记到怪物身上——
 *       镐子换到主手，方块进方块背包（没有背包就现建一个空的）。</li>
 * </ol>
 *
 * <p>线程模型：{@link #tick} 在怪物所属区域的线程上执行；掉落物实体在<b>它自己</b>的区域线程上移除，
 * 结算再切回怪物的实体调度器，不跨区域读写别的实体。</p>
 */
public final class ItemPickupService {

    private final MobInteractPlugin plugin;

    public ItemPickupService(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /** 周期性调用（由 {@code MobLifecycleListener} 的实体定时器驱动）。 */
    public void tick(Mob mob) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return;
        }
        PlacementSettings settings = plugin.settings().placement();
        if (!settings.enabled() || !settings.pickupEnabled()) {
            return;
        }
        if (!settings.worldFilter().allows(mob.getWorld()) || !settings.mobFilter().allows(mob)) {
            return;
        }
        // 正在挖掘 / 放置的怪物不要被打断。
        MobProfile profile = plugin.registry().find(mob.getUniqueId());
        if (settings.pickupOnlyEquipped() && (profile == null || !profile.equipGranted())) {
            // 没过 equip.yml 闸门的怪物不许捡东西：
            // 否则“捡来的方块 / 镐子”会让带装备的怪物比例超过配置值。
            return;
        }
        if (profile != null && profile.busy()) {
            return;
        }

        Item item = findItem(mob, settings);
        if (item == null) {
            return;
        }
        Location from = mob.getLocation();
        if (distanceSquared(item, from) <= reachSquared(settings.pickupReach())) {
            tryPickup(mob, item, settings, from);
            return;
        }
        Pathfinder pathfinder = mob.getPathfinder();
        if (pathfinder != null) {
            // 走过去；原版 AI 之后若另有安排会被覆盖，1 秒后这里会再下一次。
            pathfinder.moveTo(item.getLocation(), 1.0D);
            if (profile != null) {
                profile.setLastStatus("去捡 " + item.getItemStack().getType().name());
            }
        }
    }

    /** 半径内“值得捡”的最近掉落物；没有则返回 {@code null}。 */
    private Item findItem(Mob mob, PlacementSettings settings) {
        double radius = settings.pickupRadius();
        Item best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mob.getNearbyEntities(radius, Math.max(2.0D, radius * 0.5D), radius)) {
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            if (!wants(mob, stack.getType(), settings)) {
                continue;
            }
            double distance = distanceSquared(item, mob.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    /** 这个材质值不值得专门走过去捡。 */
    public boolean wants(Mob mob, Material material, PlacementSettings settings) {
        if (material == null || material.isAir()) {
            return false;
        }
        if (settings.pickupPickaxes() && PickedItems.isPickaxe(material)) {
            return mainHandFree(mob);
        }
        if (!settings.pickupBlocks() || !settings.hasEntries()) {
            return false;
        }
        return PickedItems.canStore(material);
    }

    /**
     * 原版拾取被本插件接管时调用：立刻把掉落物收进方块背包 / 换到主手。
     *
     * <p>调用方必须已处于该掉落物（也就是怪物）所属区域的线程上。
     * 用它可以避免“方块被原版塞进主手”，方块统一进虚拟背包。</p>
     */
    public void absorb(Mob mob, Item item, PlacementSettings settings) {
        if (mob == null || item == null || !item.isValid() || item.isDead()
                || !mob.isValid() || mob.isDead()) {
            return;
        }
        if (settings.pickupOnlyEquipped()) {
            MobProfile profile = plugin.registry().find(mob.getUniqueId());
            if (profile == null || !profile.equipGranted()) {
                // 没通过出生装备闸门：连原版自动拾取也不吸收（否则比例会超过配置值）。
                return;
            }
        }
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        ItemStack picked = stack.clone();
        item.remove();
        credit(mob, picked, settings);
    }

    /** 主手是否空着（没有手部装备槽的生物永远拿不住东西）。 */
    private boolean mainHandFree(Mob mob) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) {
            return false;
        }
        ItemStack hand = equipment.getItemInMainHand();
        return hand == null || hand.getType().isAir();
    }

    /**
     * 真正捡起来：掉落物在它自己的区域线程上移除，结算回到怪物线程。
     *
     * @param from 发起拾取时怪物的位置（不在掉落物线程上读怪物位置）
     */
    private void tryPickup(Mob mob, Item item, PlacementSettings settings, Location from) {
        double tolerance = settings.pickupReach() + 1.5D;
        plugin.schedulers().entity(item, () -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }
            if (distanceSquared(item.getLocation(), from) > tolerance * tolerance) {
                // 怪物已经走开了：这次不算捡到，等下一次扫描。
                return;
            }
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                return;
            }
            ItemStack picked = stack.clone();
            item.remove();
            plugin.schedulers().entity(mob, () -> credit(mob, picked, settings));
        });
    }

    /** 把捡到的物品记到怪物身上（怪物所属区域线程）。 */
    private void credit(Mob mob, ItemStack stack, PlacementSettings settings) {
        if (!mob.isValid() || mob.isDead() || stack == null || stack.getType().isAir()) {
            return;
        }
        MobProfile profile = plugin.registry().profile(mob);
        Material material = stack.getType();

        if (settings.pickupPickaxes() && PickedItems.isPickaxe(material)) {
            EntityEquipment equipment = mob.getEquipment();
            if (equipment == null || !mainHandFree(mob)) {
                // 拿不住（手上有别的东西 / 没有手）：丢回地上，别凭空吞掉玩家的物品。
                returnToGround(mob, stack);
                return;
            }
            // 捡到的镐子保持原版掉落逻辑（玩家击杀后能拿回来），不套用 tool.give.drop-chance。
            equipment.setItemInMainHand(new ItemStack(material));
            profile.setLastStatus("捡到镐子 " + material.name());
            return;
        }
        if (!settings.pickupBlocks() || !PickedItems.canStore(material)) {
            returnToGround(mob, stack);
            return;
        }
        PlacementEntry entry = PickedItems.entryFor(settings.entries(), material);
        int total = profile.backpackOrCreate().add(entry, stack.getAmount());
        profile.setLastStatus("捡到方块 " + material.name() + " ×" + stack.getAmount() + "（共 " + total + "）");
        plugin.heldItems().sync(mob);
    }

    /** 用不上的东西重新掉回地上（在怪物所属区域线程上调用）。 */
    private void returnToGround(Mob mob, ItemStack stack) {
        try {
            mob.getWorld().dropItem(mob.getLocation().add(0.0D, 0.5D, 0.0D), stack);
        } catch (Throwable throwable) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "扔回掉落物失败", throwable);
        }
    }

    private static double distanceSquared(Item item, Location location) {
        return distanceSquared(item.getLocation(), location);
    }

    private static double distanceSquared(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double reachSquared(double reach) {
        return reach * reach;
    }
}
