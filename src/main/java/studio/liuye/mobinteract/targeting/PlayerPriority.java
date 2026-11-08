package studio.liuye.mobinteract.targeting;

import java.util.List;
import java.util.UUID;

/**
 * “在候选目标里挑最近的玩家”的纯逻辑部分（不碰 Bukkit，方便单元测试）。
 */
public final class PlayerPriority {

    /**
     * 一个候选玩家。
     *
     * @param id 唯一标识（玩家 UUID）
     * @param x  坐标
     * @param y  坐标
     * @param z  坐标
     */
    public record Candidate(UUID id, double x, double y, double z) {
    }

    private PlayerPriority() {
    }

    /**
     * 从候选里挑出离 {@code from} 最近、且在 {@code radius} 内的那个。
     *
     * @param from       怪物位置（可为 {@code null}，此时返回 {@code null}）
     * @param candidates 候选玩家（可为 {@code null} 或空）
     * @param radius     最大距离（格，{@code <= 0} 时不过滤）
     * @return 最近的候选；没有符合条件时返回 {@code null}
     */
    public static Candidate pick(Candidate from, List<Candidate> candidates, double radius) {
        if (from == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Candidate best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            double dx = candidate.x() - from.x();
            double dy = candidate.y() - from.y();
            double dz = candidate.z() - from.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (radius > 0.0D && distance > radius * radius) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
