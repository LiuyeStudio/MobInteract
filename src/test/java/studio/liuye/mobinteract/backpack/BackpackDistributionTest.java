package studio.liuye.mobinteract.backpack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BackpackDistributionTest {

    @Test
    void everyKindGetsAtLeastOne() {
        Random random = new Random(5L);
        for (int i = 0; i < 500; i++) {
            int[] counts = BackpackDistribution.distribute(20, 4, random);
            assertEquals(4, counts.length);
            for (int count : counts) {
                assertTrue(count >= 1);
            }
            assertEquals(20, sum(counts));
        }
    }

    @Test
    void totalSmallerThanKindsTruncatesKinds() {
        int[] counts = BackpackDistribution.distribute(2, 5, new Random(1L));
        assertEquals(2, counts.length);
        assertArrayEquals(new int[] {1, 1}, counts);
    }

    @Test
    void zeroTotalProducesNoKinds() {
        assertEquals(0, BackpackDistribution.distribute(0, 3, new Random(1L)).length);
        assertEquals(0, BackpackDistribution.distribute(-5, 3, new Random(1L)).length);
    }

    @Test
    void zeroKindsProducesEmptyArray() {
        assertEquals(0, BackpackDistribution.distribute(10, 0, new Random(1L)).length);
    }

    @Test
    void singleKindTakesEverything() {
        assertArrayEquals(new int[] {7}, BackpackDistribution.distribute(7, 1, new Random(1L)));
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
