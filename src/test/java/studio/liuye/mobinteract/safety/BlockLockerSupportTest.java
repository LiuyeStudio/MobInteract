package studio.liuye.mobinteract.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * 反射解析 BlockLocker API 的单元测试（不启动服务器）。
 *
 * <p>这里用“假 API 类”模拟 BlockLocker 的两种签名，确保解析逻辑在 API 变动时能被测出来。</p>
 */
class BlockLockerSupportTest {

    /** 模拟真实的 {@code BlockLockerAPIv2#isProtected(Block)}。 */
    public static final class BlockApi {
        public static boolean isProtected(Block block) {
            return block != null;
        }
    }

    /** 模拟只有 {@code isProtected(Location)} 重载的版本。 */
    public static final class LocationApi {
        public static Boolean isProtected(Location location) {
            return location != null;
        }
    }

    /** 什么都没有的类。 */
    public static final class EmptyApi {
        public static String isProtected(String ignored) {
            return "not a boolean";
        }
    }

    @Test
    void resolvesBlockOverload() {
        assertNotNull(BlockLockerSupport.resolveApi(BlockApi.class));
    }

    @Test
    void resolvesLocationOverload() {
        assertNotNull(BlockLockerSupport.resolveApi(LocationApi.class));
    }

    @Test
    void rejectsClassesWithoutBooleanIsProtected() {
        assertNull(BlockLockerSupport.resolveApi(EmptyApi.class));
        assertNull(BlockLockerSupport.resolveApi(null));
    }

    @Test
    void protectedCheckIsFailOpen() {
        BlockLockerSupport support = BlockLockerSupport.resolveApi(BlockApi.class);
        assertNotNull(support);
        // 没有插件实例 / 方块为空时按“不保护”处理，且不会抛异常。
        assertFalse(support.isProtected(null, null));
        assertFalse(support.isProtected(null, null));
    }
}
