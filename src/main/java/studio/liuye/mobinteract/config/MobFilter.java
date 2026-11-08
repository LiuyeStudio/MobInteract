package studio.liuye.mobinteract.config;

import java.util.Set;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import studio.liuye.mobinteract.mob.HumanoidFilter;

/**
 * 怪物筛选规则（挖掘与放置各有一份）。
 *
 * <p>语义：</p>
 * <ol>
 *   <li>{@code blacklist} 永远优先：命中即拒绝，即使同时出现在白名单里。</li>
 *   <li>{@code whitelist} 里写了 {@code "*"} 时（{@code allMobs = true}），所有生物放行。</li>
 *   <li>{@code whitelist} 非空时，只有列出的生物类型放行——这就是“自定义添加其他生物种类”的入口，
 *       可以填任意 {@link EntityType}（例如 {@code IRON_GOLEM}、{@code WOLF}）。</li>
 *   <li>{@code whitelist} 留空时，退回到默认的人形怪物集合（{@link HumanoidFilter}）。</li>
 * </ol>
 *
 * @param whitelist 白名单；空集合表示使用默认人形集合
 * @param blacklist 黑名单，优先级最高
 * @param allMobs   白名单里是否写了 {@code "*"}
 */
public record MobFilter(Set<EntityType> whitelist, Set<EntityType> blacklist, boolean allMobs) {

    public static MobFilter humanoidDefault() {
        return new MobFilter(Set.of(), Set.of(), false);
    }

    /**
     * 纯判定（便于单元测试）：给定实体类型与“是否默认人形”的结果。
     *
     * @param type            实体类型
     * @param defaultHumanoid 该实体是否属于默认人形集合
     * @return 是否允许执行行为
     */
    public boolean allowsType(EntityType type, boolean defaultHumanoid) {
        if (type == null) {
            return false;
        }
        if (blacklist.contains(type)) {
            return false;
        }
        if (allMobs) {
            return true;
        }
        if (!whitelist.isEmpty()) {
            return whitelist.contains(type);
        }
        return defaultHumanoid;
    }

    /** 是否允许这个怪物执行行为。 */
    public boolean allows(Mob mob) {
        if (mob == null) {
            return false;
        }
        return allowsType(mob.getType(), HumanoidFilter.isHumanoid(mob));
    }

    /** 配置摘要，用于 /mobinteract status。 */
    public String describe() {
        String base;
        if (allMobs) {
            base = "全部生物";
        } else if (!whitelist.isEmpty()) {
            base = "白名单(" + whitelist.size() + ")";
        } else {
            base = "默认人形";
        }
        if (blacklist.isEmpty()) {
            return base;
        }
        return base + " 黑名单(" + blacklist.size() + ")";
    }
}
