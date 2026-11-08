package studio.liuye.mobinteract.mining;

/**
 * 挖掘耗时换算。
 *
 * <p>玩家基础挖掘时间用 {@code Material#getHardness()} 在运行时推算（公开 API，
 * 不硬编码方块列表）：空手挖掘的近似公式为 {@code 1.5 × 硬度} 秒。
 * 怪物耗时 = 基础时间 × 倍率，再套上下限。</p>
 */
public final class BreakTimeCalculator {

    private BreakTimeCalculator() {
    }

    /**
     * 玩家空手基础挖掘时间（秒）。
     *
     * @param hardness 方块硬度
     * @return 秒数；硬度小于 0（不可破坏）时返回 -1
     */
    public static double baseSeconds(float hardness) {
        if (hardness < 0.0F) {
            return -1.0D;
        }
        return Math.max(hardness, 0.0F) * 1.5D;
    }

    /**
     * 怪物挖掘一个方块所需时间（秒），按“空手速度 1.0”计算。
     *
     * @param hardness       方块硬度
     * @param multiplier     倍率
     * @param minSeconds     最短耗时
     * @param hardCapSeconds 耗时上限，超过即视为不可挖
     * @return 秒数；不可挖时返回 -1
     */
    public static double monsterSeconds(float hardness, double multiplier, double minSeconds,
            double hardCapSeconds) {
        return monsterSeconds(hardness, multiplier, minSeconds, hardCapSeconds, 1.0D);
    }

    /**
     * 怪物挖掘一个方块所需时间（秒）。
     *
     * <p>公式：{@code 玩家基础时间 × 倍率 ÷ 工具速度系数}。
     * 倍率以木镐为基准，工具速度系数来自 {@code tool.speed}（木镐 = 1.0）。</p>
     *
     * @param hardness       方块硬度
     * @param multiplier     倍率（木镐基准）
     * @param minSeconds     最短耗时
     * @param hardCapSeconds 耗时上限，超过即视为不可挖
     * @param toolSpeed      工具速度系数（&le; 0 时按 1.0 处理）
     * @return 秒数；不可挖时返回 -1
     */
    public static double monsterSeconds(float hardness, double multiplier, double minSeconds,
            double hardCapSeconds, double toolSpeed) {
        double base = baseSeconds(hardness);
        if (base < 0.0D) {
            return -1.0D;
        }
        double speed = toolSpeed > 0.0D ? toolSpeed : 1.0D;
        double seconds = Math.max(base * Math.max(multiplier, 0.0D) / speed, Math.max(minSeconds, 0.0D));
        if (seconds > hardCapSeconds) {
            return -1.0D;
        }
        return seconds;
    }

    /** 秒数换算成 tick（向上取整，至少 1 tick）。 */
    public static int totalTicks(double seconds) {
        if (seconds <= 0.0D) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(seconds * 20.0D));
    }
}
