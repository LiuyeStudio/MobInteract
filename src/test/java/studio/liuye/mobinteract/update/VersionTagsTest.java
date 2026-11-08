package studio.liuye.mobinteract.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link VersionTags} 的版本比较规则。 */
class VersionTagsTest {

    @Test
    void normalizeStripsPrefixAndBlanks() {
        assertEquals("1.3.0", VersionTags.normalize("v1.3.0"));
        assertEquals("1.3.0", VersionTags.normalize("  V1.3.0  "));
        assertEquals("", VersionTags.normalize(null));
    }

    @Test
    void sameVersionComparesEqual() {
        assertEquals(0, VersionTags.compare("1.3.0", "1.3.0"));
        assertEquals(0, VersionTags.compare("v1.3.0", "1.3.0"));
        // 缺省段按 0 处理。
        assertEquals(0, VersionTags.compare("1.3", "1.3.0"));
        assertEquals(0, VersionTags.compare("1.3.0.0", "1.3.0"));
    }

    @Test
    void newerMinorWins() {
        assertTrue(VersionTags.compare("1.4.0", "1.3.9") > 0);
        assertTrue(VersionTags.compare("1.3.1", "1.3.0") > 0);
        assertTrue(VersionTags.compare("2.0", "1.9.9") > 0);
    }

    @Test
    void numericSegmentsCompareAsNumbers() {
        // 字符串比较会把 1.10 排在 1.9 前面，这里必须是数值比较。
        assertTrue(VersionTags.compare("1.10.0", "1.9.9") > 0);
        assertTrue(VersionTags.compare("10", "9") > 0);
        assertTrue(VersionTags.compare("1.0.10", "1.0.9") > 0);
    }

    @Test
    void prereleaseIsOlderThanRelease() {
        assertTrue(VersionTags.compare("1.3.0-beta", "1.3.0") < 0);
        assertTrue(VersionTags.compare("1.3.0", "1.3.0-beta") > 0);
        assertTrue(VersionTags.compare("1.3.0-beta.2", "1.3.0-beta.1") > 0);
    }

    @Test
    void isNewerMatchesCompare() {
        assertTrue(VersionTags.isNewer("v1.4.0", "1.3.0"));
        assertFalse(VersionTags.isNewer("1.3.0", "1.3.0"));
        assertFalse(VersionTags.isNewer("1.2.9", "1.3.0"));
    }

    @Test
    void emptyVersionsBehaveAsZero() {
        assertEquals(0, VersionTags.compare("", "0"));
        assertTrue(VersionTags.compare("", "1.0") < 0);
    }

    @Test
    void veryLongNumericSegmentsDoNotOverflow() {
        assertTrue(VersionTags.compare("1.99999999999999999999", "1.99999999999999999998") > 0);
        assertEquals(0, VersionTags.compare("1.000001", "1.1"));
    }
}
