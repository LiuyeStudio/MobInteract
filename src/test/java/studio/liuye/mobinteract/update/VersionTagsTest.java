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

    @Test
    void prereleaseNumbersCompareAsNumbers() {
        // 旧实现整串比字符串，rc2 反而排在 rc10 后面。
        assertTrue(VersionTags.compare("1.4.0-rc10", "1.4.0-rc2") > 0);
        assertTrue(VersionTags.compare("1.4.0-rc2", "1.4.0-rc10") < 0);
    }

    @Test
    void numericPrereleaseIsOlderThanAlpha() {
        // 语义化版本：纯数字标识符优先级低于字母标识符。
        assertTrue(VersionTags.compare("1.0.0-1", "1.0.0-alpha") < 0);
        // 字段少的更旧。
        assertTrue(VersionTags.compare("1.0.0-alpha", "1.0.0-alpha.1") < 0);
    }

    @Test
    void buildMetadataIsIgnored() {
        assertEquals(0, VersionTags.compare("1.4.0+build.7", "1.4.0"));
        assertEquals(0, VersionTags.compare("v1.4.0+20260101", "1.4.0"));
        assertFalse(VersionTags.isNewer("1.4.0+build.7", "1.4.0"));
    }

    @Test
    void wordyPrefixIsStripped() {
        // release-1.4.0 是 GitHub 上常见的手写标签：破折号前的单词不是版本号。
        assertTrue(VersionTags.compare("release-1.4.0", "1.3.9") > 0);
        assertEquals(0, VersionTags.compare("release-1.4.0", "1.4.0"));
    }
}
