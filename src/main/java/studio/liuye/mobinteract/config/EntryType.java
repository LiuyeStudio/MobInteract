package studio.liuye.mobinteract.config;

/** 可放置条目的类别。 */
public enum EntryType {
    /** 普通方块。 */
    BLOCK,
    /** 点燃的 TNT（不经过 TNT 方块中间态）。 */
    TNT,
    /** 桶装流体（水 / 熔岩）。 */
    FLUID
}
