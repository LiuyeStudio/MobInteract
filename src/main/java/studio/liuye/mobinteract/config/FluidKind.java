package studio.liuye.mobinteract.config;

import org.bukkit.Material;

/** 桶装流体的种类。 */
public enum FluidKind {
    WATER(Material.WATER),
    LAVA(Material.LAVA);

    private final Material material;

    FluidKind(Material material) {
        this.material = material;
    }

    public Material material() {
        return material;
    }

    /** 解析配置里的名称，非法值返回 null（由调用方记录警告）。 */
    public static FluidKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (FluidKind kind : values()) {
            if (kind.name().equalsIgnoreCase(raw.trim())) {
                return kind;
            }
        }
        return null;
    }
}
