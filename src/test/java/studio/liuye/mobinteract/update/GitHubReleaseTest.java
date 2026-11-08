package studio.liuye.mobinteract.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link GitHubRelease} 的返回体解析（不联网）。 */
class GitHubReleaseTest {

    private static final String BODY = """
            [
              {
                "url": "https://api.github.com/repos/LiuyeStudio/MobInteract/releases/11",
                "tag_name": "v1.2.0",
                "name": "MobInteract 1.2.0",
                "draft": false,
                "prerelease": false,
                "author": { "login": "nukebean", "html_url": "https://github.com/nukebean" },
                "html_url": "https://github.com/LiuyeStudio/MobInteract/releases/tag/v1.2.0",
                "body": "修复了 \\"背包\\" 判定，注意 \\\\ 反斜杠",
                "assets": [ { "name": "MobInteract-1.2.0.jar", "size": 123 } ]
              },
              {
                "tag_name": "v1.3.0-beta.1",
                "name": "beta",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/LiuyeStudio/MobInteract/releases/tag/v1.3.0-beta.1"
              },
              {
                "tag_name": "v1.4.0",
                "draft": true,
                "prerelease": false,
                "html_url": "https://github.com/LiuyeStudio/MobInteract/releases/tag/v1.4.0"
              }
            ]
            """;

    @Test
    void picksFirstStableRelease() {
        GitHubRelease release = GitHubRelease.pick(BODY, false);
        assertNotNull(release);
        assertEquals("v1.2.0", release.tag());
        assertEquals("https://github.com/LiuyeStudio/MobInteract/releases/tag/v1.2.0", release.url());
        assertEquals("MobInteract 1.2.0", release.name());
        assertFalse(release.prerelease());
    }

    @Test
    void prereleaseOnlyWhenAsked() {
        String body = """
                [
                  { "tag_name": "v2.0.0-rc1", "draft": false, "prerelease": true,
                    "html_url": "https://example.invalid/rc1" },
                  { "tag_name": "v1.9.0", "draft": false, "prerelease": false,
                    "html_url": "https://example.invalid/1.9.0" }
                ]
                """;
        assertEquals("v1.9.0", GitHubRelease.pick(body, false).tag());
        assertEquals("v2.0.0-rc1", GitHubRelease.pick(body, true).tag());
    }

    @Test
    void skipsDrafts() {
        String body = """
                [
                  { "tag_name": "v9.9.9", "draft": true, "prerelease": false },
                  { "tag_name": "v1.0.1", "draft": false, "prerelease": false }
                ]
                """;
        assertEquals("v1.0.1", GitHubRelease.pick(body, true).tag());
    }

    @Test
    void returnsNullWithoutUsableRelease() {
        assertNull(GitHubRelease.pick("[]", false));
        assertNull(GitHubRelease.pick("", false));
        assertNull(GitHubRelease.pick(null, false));
        // 仓库不存在时 GitHub 返回的是单个错误对象，不是数组。
        assertNull(GitHubRelease.pick("{\"message\":\"Not Found\"}", false));
        assertNull(GitHubRelease.pick("[{\"tag_name\":\"\"}]", false));
    }

    @Test
    void objectsSplitsTopLevelOnly() {
        List<String> objects = GitHubRelease.objects(BODY);
        // 第三个对象是草稿，但切分不关心语义：三个顶层对象都应该拿到。
        assertEquals(3, objects.size());
        assertTrue(objects.get(0).startsWith("{"));
        assertTrue(objects.get(0).contains("\"assets\""));
        assertFalse(objects.get(0).contains("v1.3.0-beta.1"));
    }

    @Test
    void stringHandlesEscapes() {
        String object = GitHubRelease.objects(BODY).get(0);
        String body = GitHubRelease.string(object, "body");
        assertNotNull(body);
        assertTrue(body.contains("\"背包\""), body);
        assertTrue(body.contains("\\"), body);
        // 不存在的字段返回 null，不是空串。
        assertNull(GitHubRelease.string(object, "missing"));
    }

    @Test
    void boolReadsRealBooleans() {
        String object = GitHubRelease.objects(BODY).get(1);
        assertTrue(GitHubRelease.bool(object, "prerelease"));
        assertFalse(GitHubRelease.bool(object, "draft"));
        assertFalse(GitHubRelease.bool(object, "missing"));
        // 字符串 "true" 不能被当成布尔值。
        assertFalse(GitHubRelease.bool("{\"draft\":\"true\"}", "draft"));
    }
}
