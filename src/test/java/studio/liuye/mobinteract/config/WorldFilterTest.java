package studio.liuye.mobinteract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldFilterTest {

    @Test
    void emptyWhitelistAllowsEveryWorld() {
        WorldFilter filter = WorldFilter.all();
        assertTrue(filter.allowsName("world"));
        assertTrue(filter.allowsName("resource_world"));
        assertEquals("全部世界", filter.describe());
    }

    @Test
    void whitelistRestrictsToConfiguredWorlds() {
        WorldFilter filter = new WorldFilter(Set.of("world"), Set.of());
        assertTrue(filter.allowsName("world"));
        assertFalse(filter.allowsName("spawn"));
    }

    @Test
    void blacklistWinsOverWhitelist() {
        WorldFilter filter = new WorldFilter(Set.of("world"), Set.of("world"));
        assertFalse(filter.allowsName("world"));
    }

    @Test
    void namesAreCaseInsensitive() {
        WorldFilter filter = new WorldFilter(Set.of("world_nether"), Set.of());
        assertTrue(filter.allowsName("WORLD_NETHER"));
        assertTrue(filter.allowsName("  World_Nether  "));
    }

    @Test
    void blankNameIsRejected() {
        assertFalse(WorldFilter.all().allowsName(""));
        assertFalse(WorldFilter.all().allowsName(null));
    }

    @Test
    void describeShowsCounts() {
        WorldFilter filter = new WorldFilter(Set.of("a", "b"), Set.of("c"));
        assertEquals("白名单(2) 黑名单(1)", filter.describe());
    }
}
