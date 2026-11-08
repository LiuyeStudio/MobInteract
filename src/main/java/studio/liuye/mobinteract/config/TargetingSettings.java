package studio.liuye.mobinteract.config;

/**
 * “优先追杀玩家”配置。
 *
 * @param enabled         总开关
 * @param radius          索敌半径（格）：玩家在这个范围内才会被优先锁定
 * @param intervalTicks   检查间隔（tick）：越小越灵敏，越大越省
 * @param requireLineOfSight 是否要求能看见玩家（{@code false} = 隔着墙也会朝玩家走，
 *                           配合插件的挖掘 / 搭路就是“拆墙过来”）
 * @param keepVillagerTarget 附近没有玩家时，是否保留原版选的目标（{@code false} = 主动清空）
 */
public record TargetingSettings(
        boolean enabled,
        double radius,
        long intervalTicks,
        boolean requireLineOfSight,
        boolean keepVillagerTarget) {

    /** 默认值：开启、16 格、每 10 tick 检查一次、不要求视线、没有玩家时保留原目标。 */
    public static TargetingSettings defaults() {
        return new TargetingSettings(true, 16.0D, 10L, false, true);
    }
}
