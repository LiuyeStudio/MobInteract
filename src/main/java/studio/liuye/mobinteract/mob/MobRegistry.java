package studio.liuye.mobinteract.mob;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Mob;

/** 怪物 UUID → 运行期状态的注册表。 */
public final class MobRegistry {

    /**
     * 登记表上限。
     *
     * <p>区块卸载时我们<b>故意保留</b>档案（否则走远再回来会重新掷骰，等于免费刷方块），
     * 代价是“进了未加载区块就再也没回来的怪”会留下一条轻量档案。单条只有几百字节，
     * 但也不能无限涨：超过这个数量就整表清空一次，让后续生物重新建档（性能无损，
     * 只是那批怪会重新掷一次出生装备）。</p>
     */
    private static final int MAX_PROFILES = 50_000;

    private final Map<UUID, MobProfile> profiles = new ConcurrentHashMap<>();

    public MobProfile profile(Mob mob) {
        if (profiles.size() > MAX_PROFILES) {
            profiles.clear();
        }
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
