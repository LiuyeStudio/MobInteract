package studio.liuye.mobinteract.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WeightedSamplerTest {

    private static final class Item {
        private final String name;
        private final double weight;

        private Item(String name, double weight) {
            this.name = name;
            this.weight = weight;
        }
    }

    private static List<Item> items() {
        return List.of(new Item("heavy", 10.0D), new Item("light", 1.0D), new Item("zero", 0.0D));
    }

    @Test
    void pickNeverReturnsZeroWeightItem() {
        Random random = new Random(42L);
        for (int i = 0; i < 5000; i++) {
            Item picked = WeightedSampler.pick(items(), item -> item.weight, random);
            assertNotNull(picked);
            assertTrue(picked.weight > 0.0D, "不应抽中权重为 0 的条目");
        }
    }

    @Test
    void pickFollowsWeights() {
        Random random = new Random(7L);
        int heavy = 0;
        int light = 0;
        for (int i = 0; i < 20_000; i++) {
            Item picked = WeightedSampler.pick(items(), item -> item.weight, random);
            if (picked.name.equals("heavy")) {
                heavy++;
            } else if (picked.name.equals("light")) {
                light++;
            }
        }
        double ratio = heavy / (double) light;
        assertEquals(10.0D, ratio, 0.6D, "权重 10:1 的抽取比例应接近 10:1");
    }

    @Test
    void pickReturnsNullWithoutCandidates() {
        assertNull(WeightedSampler.pick(List.of(), item -> 1.0D, new Random(1L)));
        assertNull(WeightedSampler.pick(items(), item -> 0.0D, new Random(1L)));
    }

    @Test
    void sampleWithoutReplacementReturnsDistinctItems() {
        Random random = new Random(3L);
        List<Item> pool = new ArrayList<>(items());
        List<Item> picked = WeightedSampler.sampleWithoutReplacement(pool, item -> item.weight, 2, random);
        assertEquals(2, picked.size());
        assertEquals("heavy", picked.get(0).name);
        assertTrue(picked.stream().noneMatch(item -> item.name.equals("zero")));
        assertEquals(2, picked.stream().map(item -> item.name).distinct().count());
    }

    @Test
    void sampleWithoutReplacementStopsWhenPoolIsSmaller() {
        List<Item> picked = WeightedSampler.sampleWithoutReplacement(items(), item -> item.weight, 10,
                new Random(11L));
        assertEquals(2, picked.size(), "只有两个正权重条目可被抽出");
    }
}
