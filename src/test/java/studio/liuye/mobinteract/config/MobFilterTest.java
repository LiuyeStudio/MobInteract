package studio.liuye.mobinteract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class MobFilterTest {

    @Test
    void emptyWhitelistFallsBackToHumanoidDefault() {
        MobFilter filter = MobFilter.humanoidDefault();
        assertTrue(filter.allowsType(EntityType.ZOMBIE, true));
        assertFalse(filter.allowsType(EntityType.WOLF, false));
    }

    @Test
    void explicitWhitelistAllowsNonHumanoidTypes() {
        MobFilter filter = new MobFilter(Set.of(EntityType.IRON_GOLEM, EntityType.WOLF), Set.of(), false);
        assertTrue(filter.allowsType(EntityType.IRON_GOLEM, false), "自定义添加的非人形生物应被接管");
        assertTrue(filter.allowsType(EntityType.WOLF, false));
        assertFalse(filter.allowsType(EntityType.ZOMBIE, true), "白名单非空时以白名单为准");
    }

    @Test
    void wildcardAllowsEverything() {
        MobFilter filter = new MobFilter(Set.of(), Set.of(), true);
        assertTrue(filter.allowsType(EntityType.ZOMBIE, true));
        assertTrue(filter.allowsType(EntityType.IRON_GOLEM, false));
        assertTrue(filter.allowsType(EntityType.BAT, false));
    }

    @Test
    void blacklistWinsOverWhitelistAndWildcard() {
        MobFilter whitelist = new MobFilter(Set.of(EntityType.ZOMBIE, EntityType.IRON_GOLEM),
                Set.of(EntityType.IRON_GOLEM), false);
        assertTrue(whitelist.allowsType(EntityType.ZOMBIE, true));
        assertFalse(whitelist.allowsType(EntityType.IRON_GOLEM, false));

        MobFilter wildcard = new MobFilter(Set.of(), Set.of(EntityType.VILLAGER), true);
        assertTrue(wildcard.allowsType(EntityType.ZOMBIE, true));
        assertFalse(wildcard.allowsType(EntityType.VILLAGER, true));
    }

    @Test
    void describeSummarizesConfiguration() {
        assertEquals("默认人形", MobFilter.humanoidDefault().describe());
        assertEquals("全部生物", new MobFilter(Set.of(), Set.of(), true).describe());
        assertEquals("白名单(2)", new MobFilter(Set.of(EntityType.ZOMBIE, EntityType.WOLF), Set.of(), false)
                .describe());
        assertEquals("全部生物 黑名单(1)", new MobFilter(Set.of(), Set.of(EntityType.VILLAGER), true)
                .describe());
    }

    @Test
    void nullTypeIsRejected() {
        assertFalse(MobFilter.humanoidDefault().allowsType(null, true));
    }
}
