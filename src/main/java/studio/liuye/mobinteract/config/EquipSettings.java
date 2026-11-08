package studio.liuye.mobinteract.config;

import java.util.Locale;
import java.util.Map;

/**
 * 出生装备概率（镐子 + 方块的**总闸门**）。
 *
 * <p>只有通过这道闸门的怪物才会拿到镐子或方块，所以“带镐子 + 带方块”的怪物比例
 * **加起来**不会超过这里配置的值。闸门之内再按 {@code mining.yml} 的
 * {@code tool.give.chance} 与 {@code placement.yml} 的 {@code backpack.chance} 细分。</p>
 *
 * @param chance 全局概率（0..1）
 * @param worlds 按世界覆盖的概率（键为世界名，不区分大小写）
 */
public record EquipSettings(double chance, Map<String, Double> worlds) {

    /** 该世界实际使用的概率。 */
    public double chanceFor(String worldName) {
        if (worldName == null || worlds.isEmpty()) {
            return chance;
        }
        String key = worldName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Double> entry : worlds.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(key)) {
                return entry.getValue();
            }
        }
        return chance;
    }
}
