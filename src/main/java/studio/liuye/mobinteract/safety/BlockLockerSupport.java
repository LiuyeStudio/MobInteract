package studio.liuye.mobinteract.safety;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * BlockLocker 兼容层（软依赖）。
 *
 * <p>BlockLocker 用告示牌保护箱子 / 门 / 活板门等方块，它提供静态 API：
 * {@code nl.rutgerkok.blocklocker.BlockLockerAPIv2#isProtected(Block)}（另有 {@code Location} 重载）。
 * 这里用反射调用，避免把 BlockLocker 变成编译期依赖；插件不存在或 API 变化时自动降级为“不设保护”。</p>
 */
public final class BlockLockerSupport {

    private static final String PLUGIN_NAME = "BlockLocker";
    private static final String API_CLASS = "nl.rutgerkok.blocklocker.BlockLockerAPIv2";

    private final Method blockMethod;
    private final Method locationMethod;
    private boolean warned;

    private BlockLockerSupport(Method blockMethod, Method locationMethod) {
        this.blockMethod = blockMethod;
        this.locationMethod = locationMethod;
    }

    /**
     * 尝试解析 BlockLocker API。
     *
     * @param plugin 本插件（用于日志）
     * @return 兼容层实例；BlockLocker 未安装或 API 不可用时返回 {@code null}
     */
    public static BlockLockerSupport resolve(Plugin plugin) {
        Plugin blockLocker = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (blockLocker == null || !blockLocker.isEnabled()) {
            return null;
        }
        try {
            Class<?> api = Class.forName(API_CLASS);
            BlockLockerSupport support = resolveApi(api);
            if (support == null) {
                plugin.getLogger().warning("检测到 BlockLocker，但没有找到 isProtected(...) 方法，本次按“不设保护”运行");
                return null;
            }
            plugin.getLogger().info("已接入 BlockLocker 保护判定：怪物不会挖 / 放被保护的方块");
            return support;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("检测到 BlockLocker，但无法解析其保护 API（"
                    + throwable.getClass().getSimpleName() + "），本次按“不设保护”运行");
            return null;
        }
    }

    /**
     * 从一个 API 类里解析保护判定（纯逻辑，便于单元测试）。
     *
     * @param api BlockLocker 的 API 类
     * @return 兼容层实例；类里没有合适的 {@code isProtected(...)} 时返回 {@code null}
     */
    static BlockLockerSupport resolveApi(Class<?> api) {
        if (api == null) {
            return null;
        }
        Method blockMethod = findMethod(api, Block.class);
        Method locationMethod = blockMethod == null ? findMethod(api, org.bukkit.Location.class) : null;
        if (blockMethod == null && locationMethod == null) {
            return null;
        }
        return new BlockLockerSupport(blockMethod, locationMethod);
    }

    private static Method findMethod(Class<?> api, Class<?> parameter) {
        for (String name : new String[] {"isProtected", "isSignProtected", "isContainerProtected"}) {
            try {
                Method method = api.getMethod(name, parameter);
                if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (Throwable ignored) {
                // 换下一个候选名字
            }
        }
        return null;
    }

    /**
     * 方块是否被 BlockLocker 保护。
     *
     * <p>任何异常都按“不保护”处理（fail-open），并每个实例只告警一次，
     * 避免一个 API 变动把整个服务器的怪物行为卡死。</p>
     */
    public boolean isProtected(Plugin plugin, Block block) {
        if (block == null) {
            return false;
        }
        try {
            Object result = blockMethod != null
                    ? blockMethod.invoke(null, block)
                    : locationMethod.invoke(null, block.getLocation());
            return Boolean.TRUE.equals(result);
        } catch (Throwable throwable) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("调用 BlockLocker 保护判定失败，之后按“不保护”处理："
                        + throwable.getClass().getSimpleName());
            }
            return false;
        }
    }
}
