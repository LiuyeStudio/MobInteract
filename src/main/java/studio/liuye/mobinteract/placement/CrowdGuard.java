package studio.liuye.mobinteract.placement;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.backpack.MobBackpack;
import studio.liuye.mobinteract.mob.MobProfile;

/**
 * 防止“带方块的怪物”挤在同一格上，并让它们学会绕路。
 *
 * <p>一群怪物同时追一个玩家时，它们会互相推挤、叠在同一个方块上，结果是：
 * 谁也放不下方块、还互相把对方挤进方块里。这里提供三件事：</p>
 * <ul>
 *   <li>{@link #isCrowded(Mob, double)}：判断身边是否已有另一只“带方块的怪物”贴得太近；</li>
 *   <li>{@link #spread(Mob, double, long)}：把挤在一起的怪物往远离同伴的方向轻推一下（带冷却）；</li>
 *   <li>{@link #detour(Mob, double, long)}：给被挤住的怪物规划一个“侧向绕行点”并交给原版寻路，
 *       让它从同伴旁边绕过去，而不是所有人排成一条直线互相顶。</li>
 * </ul>
 *
 * <p>放置行为在“挤在一起”时会主动让位：不放置，先散开。</p>
 */
public final class CrowdGuard {

    private final MobInteractPlugin plugin;

    public CrowdGuard(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    /** 身边是否已有另一只带方块的怪物（排除自己与空的背包）。 */
    public boolean isCrowded(Mob mob, double radius) {
        return nearestBackpackMob(mob, radius) != null;
    }

    /**
     * 往远离最近同伴的方向轻推一下。
     *
     * @param cooldownMillis 同一只怪物的最小推动间隔，避免抖动
     */
    public void spread(Mob mob, double radius, long cooldownMillis) {
        MobProfile profile = plugin.registry().profile(mob);
        long now = System.currentTimeMillis();
        if (now - profile.lastSpreadAt() < cooldownMillis) {
            return;
        }
        Mob other = nearestBackpackMob(mob, radius);
        if (other == null) {
            return;
        }
        Vector away = mob.getLocation().toVector().subtract(other.getLocation().toVector()).setY(0.0D);
        if (away.lengthSquared() < 1.0E-4D) {
            // 完全重叠：给一个随机水平方向。
            double angle = plugin.random().nextDouble() * Math.PI * 2.0D;
            away = new Vector(Math.cos(angle), 0.0D, Math.sin(angle));
        }
        away.normalize().multiply(0.35D).setY(0.2D);
        mob.setVelocity(away);
        profile.setLastSpreadAt(now);
        profile.setLastStatus("让位：避免与同伴挤在同一格");
    }

    /**
     * 被同伴挤住时，给原版寻路设置一个侧向绕行点。
     *
     * <p>算法：取“自己 → 目标”的水平方向，取它的垂直方向作为侧向，分别检查左右两侧
     * 2.5 格与“后退 + 侧向”1.5 格的落点是否可站立；优先选离拥挤同伴更远的一侧。
     * 只要有一个落点可用就交给 {@code Pathfinder#moveTo}，让怪物绕过去。</p>
     *
     * @return true 表示已经给它设置了绕行路径
     */
    public boolean detour(Mob mob, double radius, long cooldownMillis) {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isValid()) {
            return false;
        }
        MobProfile profile = plugin.registry().profile(mob);
        long now = System.currentTimeMillis();
        if (now - profile.lastDetourAt() < cooldownMillis) {
            return false;
        }
        Mob other = nearestManagedMob(mob, radius);
        if (other == null) {
            return false;
        }

        Location base = mob.getLocation();
        Vector toTarget = target.getLocation().toVector().subtract(base.toVector()).setY(0.0D);
        if (toTarget.lengthSquared() < 1.0E-4D) {
            return false;
        }
        toTarget.normalize();
        Vector left = new Vector(-toTarget.getZ(), 0.0D, toTarget.getX()).normalize();
        Vector right = left.clone().multiply(-1.0D);

        // 左右两侧各取“远离拥挤同伴”的那一边优先。
        Vector side = left;
        if (other.getLocation().distanceSquared(base.clone().add(right.clone().multiply(2.5D)))
                > other.getLocation().distanceSquared(base.clone().add(left.clone().multiply(2.5D)))) {
            side = right;
        }

        Location[] candidates = {
            base.clone().add(side.clone().multiply(2.5D)),
            base.clone().add(side.clone().multiply(1.5D)).add(toTarget.clone().multiply(-1.5D)),
            base.clone().add(side.clone().multiply(-3.0D))
        };
        for (Location candidate : candidates) {
            if (!walkable(candidate)) {
                continue;
            }
            if (mob.getPathfinder().moveTo(candidate, 1.0D)) {
                profile.setLastDetourAt(now);
                profile.setLastStatus("绕行：从同伴旁边绕过去");
                return true;
            }
        }
        return false;
    }

    /** 该位置能否站立：脚下要踩得住，上方两格要通透。 */
    private boolean walkable(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        if (location.getY() < world.getMinHeight() + 1 || location.getY() >= world.getMaxHeight() - 2) {
            return false;
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        try {
            Block feet = location.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            Block below = feet.getRelative(0, -1, 0);
            return feet.isPassable() && head.isPassable() && !below.isPassable();
        } catch (Throwable throwable) {
            // 区域迁移期间读方块可能失败：按不可走处理。
            return false;
        }
    }

    /** 找出半径内最近的“本插件管理”的其它怪物（不要求有背包），用于绕行判断。 */
    private Mob nearestManagedMob(Mob mob, double radius) {
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mob.getNearbyEntities(radius, 2.0D, radius)) {
            if (!(entity instanceof Mob other) || other == mob || !other.isValid() || other.isDead()) {
                continue;
            }
            MobProfile profile = plugin.registry().find(other.getUniqueId());
            if (profile == null || profile.busy()) {
                continue;
            }
            double distance = other.getLocation().distanceSquared(mob.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /** 找出半径内最近的、背包里还有方块的其它怪物。 */
    private Mob nearestBackpackMob(Mob mob, double radius) {
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mob.getNearbyEntities(radius, 2.0D, radius)) {
            if (!(entity instanceof Mob other) || other == mob || !other.isValid() || other.isDead()) {
                continue;
            }
            MobProfile profile = plugin.registry().find(other.getUniqueId());
            if (profile == null) {
                continue;
            }
            MobBackpack backpack = profile.backpack();
            if (backpack == null || backpack.isEmpty()) {
                continue;
            }
            double distance = other.getLocation().distanceSquared(mob.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }
}
