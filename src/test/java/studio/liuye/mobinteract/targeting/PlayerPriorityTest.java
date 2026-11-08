package studio.liuye.mobinteract.targeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerPriorityTest {

    private static PlayerPriority.Candidate at(double x, double y, double z) {
        return new PlayerPriority.Candidate(UUID.randomUUID(), x, y, z);
    }

    @Test
    void picksTheNearestPlayer() {
        PlayerPriority.Candidate near = at(3, 0, 0);
        PlayerPriority.Candidate far = at(10, 0, 0);

        assertEquals(near, PlayerPriority.pick(at(0, 0, 0), List.of(far, near), 16.0D));
    }

    @Test
    void ignoresPlayersOutsideRadius() {
        PlayerPriority.Candidate outside = at(20, 0, 0);

        assertNull(PlayerPriority.pick(at(0, 0, 0), List.of(outside), 16.0D));
        assertEquals(outside, PlayerPriority.pick(at(0, 0, 0), List.of(outside), 0.0D),
                "半径 <= 0 表示不过滤");
    }

    @Test
    void handlesEmptyAndNull() {
        assertNull(PlayerPriority.pick(at(0, 0, 0), List.of(), 16.0D));
        assertNull(PlayerPriority.pick(at(0, 0, 0), null, 16.0D));
        assertNull(PlayerPriority.pick(null, List.of(at(1, 0, 0)), 16.0D));
    }

    @Test
    void distanceUsesThreeDimensions() {
        // 正上方的玩家也要算进距离（爬高的目标同样优先）。
        PlayerPriority.Candidate above = at(0, 6, 0);
        PlayerPriority.Candidate side = at(7, 0, 0);

        assertEquals(above, PlayerPriority.pick(at(0, 0, 0), List.of(side, above), 16.0D));
    }
}
