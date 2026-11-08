package studio.liuye.mobinteract.config;

/**
 * 一次重载得到的完整配置快照（挖掘 + 放置）。
 *
 * <p>不可变对象，通过 {@code volatile} 引用整体替换，保证区域线程读到的
 * 永远是一份自洽的配置。</p>
 */
public record Settings(MiningSettings mining, PlacementSettings placement, EquipSettings equip,
        TargetingSettings targeting) {
}
