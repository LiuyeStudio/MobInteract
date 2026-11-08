package studio.liuye.mobinteract.config;

import java.util.Locale;
import java.util.Set;
import org.bukkit.World;

/**
 * 世界筛选规则（挖掘与放置各有一份）。
 *
 * <p>语义：</p>
 * <ol>
 *   <li>{@code blacklist} 永远优先：命中即拒绝；</li>
 *   <li>{@code whitelist} 非空时，只在这些世界生效；</li>
 *   <li>{@code whitelist} 留空时，所有世界生效。</li>
 * </ol>
 *
 * <p>世界名不区分大小写，便于手写配置。</p>
 *
 * @param whitelist 启用的世界名（小写）；空集合表示所有世界
 * @param blacklist 禁用的世界名（小写），优先级最高
 */
public record WorldFilter(Set<String> whitelist, Set<String> blacklist) {

    public static WorldFilter all() {
        return new WorldFilter(Set.of(), Set.of());
    }

    /** 该世界是否允许执行行为。 */
    public boolean allows(World world) {
        return world != null && allowsName(world.getName());
    }

    /** 按世界名判断（便于单元测试与命令展示）。 */
    public boolean allowsName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) {
            return false;
        }
        if (whitelist.isEmpty()) {
            return true;
        }
        return whitelist.contains(key);
    }

    /** 配置摘要，用于 /mobinteract status。 */
    public String describe() {
        String base;
        if (whitelist.isEmpty()) {
            base = "全部世界";
        } else {
            base = "白名单(" + whitelist.size() + ")";
        }
        if (blacklist.isEmpty()) {
            return base;
        }
        return base + " 黑名单(" + blacklist.size() + ")";
    }
}
