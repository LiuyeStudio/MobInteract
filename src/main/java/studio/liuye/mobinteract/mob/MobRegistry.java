package studio.liuye.mobinteract.mob;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Mob;

/** 怪物 UUID → 运行期状态的注册表。 */
public final class MobRegistry {

    private final Map<UUID, MobProfile> profiles = new ConcurrentHashMap<>();

    public MobProfile profile(Mob mob) {
        return profiles.computeIfAbsent(mob.getUniqueId(), MobProfile::new);
    }

    public MobProfile find(UUID entityId) {
        return profiles.get(entityId);
    }

    public void remove(UUID entityId) {
        profiles.remove(entityId);
    }

    public int size() {
        return profiles.size();
    }

    public void clear() {
        profiles.clear();
    }

    /** 所有已登记怪物累计的竖直垫高格数（诊断用）。 */
    public int totalPillarSteps() {
        int total = 0;
        for (MobProfile profile : profiles.values()) {
            total += profile.pillarSteps();
        }
        return total;
    }
}
