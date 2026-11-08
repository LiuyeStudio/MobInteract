package studio.liuye.mobinteract.mob;

import java.util.Set;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Illager;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Zombie;

/**
 * “人形怪物”判定。
 *
 * <p>默认人形集合用公开的实体接口判定：<b>僵尸系（含溺尸、尸壳、僵尸村民、僵尸猪灵）、
 * 骷髅系、灾厄村民系（Illager）、女巫、猪灵系</b>。
 * 配置里的 {@code mob-whitelist} 为空时使用该集合；非空时以配置为准。</p>
 *
 * <p><b>村民 / 流浪商人这类友好生物默认不在集合里</b>：它们不会主动攻击玩家，
 * 让它们去搭方块、挖方块只会显得莫名其妙。需要时用
 * {@code mob-whitelist: [VILLAGER, WANDERING_TRADER]} 显式加回来。</p>
 *
 * <p>注意：{@code Drowned}（溺尸）在 Bukkit 里就是 {@link Zombie} 的子类，所以默认已经接管；
 * 它只有在背包里有方块 / 主手有镐子时才真的会去搭路或挖掘——没方块、没镐子的溺尸
 * 只会正常游泳追击，不会为了“上岸”到处乱跑。</p>
 */
public final class HumanoidFilter {

    private HumanoidFilter() {
    }

    public static boolean isHumanoid(Entity entity) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        return entity instanceof Zombie
                || entity instanceof AbstractSkeleton
                || entity instanceof Illager
                || entity instanceof PiglinAbstract
                || entity instanceof Witch;
    }

    /** 该怪物是否允许执行行为：白名单为空时按默认人形判定。 */
    public static boolean allowed(Mob mob, Set<EntityType> whitelist) {
        if (whitelist.isEmpty()) {
            return isHumanoid(mob);
        }
        return whitelist.contains(mob.getType());
    }
}
