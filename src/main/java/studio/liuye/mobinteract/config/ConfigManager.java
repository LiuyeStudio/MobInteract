package studio.liuye.mobinteract.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

/**
 * 两个独立配置文件的加载与热重载。
 *
 * <p>挖掘与放置分别放在 {@code mining.yml} 与 {@code placement.yml}，
 * 互不影响。重载只在全局区域线程上进行，产出新的不可变 {@link Settings}
 * 后一次性替换引用，不影响正在运行的实体任务。</p>
 */
public final class ConfigManager {

    public static final String MINING_FILE = "mining.yml";
    public static final String PLACEMENT_FILE = "placement.yml";
    /** 出生装备总概率（镐子 + 方块），支持按世界覆盖。 */
    public static final String EQUIP_FILE = "equip.yml";
    /** 索敌偏好（优先追杀玩家）。 */
    public static final String TARGETING_FILE = "targeting.yml";
    /** 检查更新（GitHub Releases）。 */
    public static final String UPDATE_FILE = "update.yml";

    /** 配置结构版本：写进 placement.yml 的 {@code config-version}，用于识别旧文件并做迁移。 */
    public static final int CONFIG_VERSION = 2;

    /** 新版 {@code placement.max-per-chase} 默认值（要大于背包库存，否则搭路会中途停手）。 */
    private static final int DEFAULT_MAX_PER_CHASE = 32;

    /** 旧版（config-version 1）的默认值，用来识别“用户其实没改过这一项”。 */
    private static final int LEGACY_MAX_PER_CHASE = 10;

    private final Plugin plugin;
    private volatile Settings settings;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 首次启动时把 jar 内的默认配置写到 data 目录。 */
    public void saveDefaultsIfMissing() {
        for (String name : new String[] {MINING_FILE, PLACEMENT_FILE, EQUIP_FILE, TARGETING_FILE, UPDATE_FILE}) {
            File file = new File(plugin.getDataFolder(), name);
            if (!file.exists()) {
                plugin.saveResource(name, false);
            }
        }
    }

    public Settings current() {
        Settings snapshot = settings;
        if (snapshot == null) {
            throw new IllegalStateException("配置尚未加载");
        }
        return snapshot;
    }

    /**
     * 解析 {@code equip.yml}：出生装备总概率 + 按世界覆盖。
     *
     * <p>没写这一项时默认 0.10（主世界最多一成怪物带镐子 / 方块）。</p>
     */
    /** 解析 {@code targeting.yml}：优先追杀玩家的开关与参数。 */
    private TargetingSettings parseTargeting(YamlConfiguration config) {
        TargetingSettings defaults = TargetingSettings.defaults();
        if (config == null) {
            return defaults;
        }
        boolean enabled = config.getBoolean("enabled", defaults.enabled());
        double radius = clampDouble(config.getDouble("radius", defaults.radius()), 1.0D, 128.0D);
        long interval = clampInt(config.getInt("interval-ticks", (int) defaults.intervalTicks()), 1, 200);
        boolean lineOfSight = config.getBoolean("require-line-of-sight", defaults.requireLineOfSight());
        boolean keepTarget = config.getBoolean("keep-villager-target", defaults.keepVillagerTarget());
        return new TargetingSettings(enabled, radius, interval, lineOfSight, keepTarget);
    }

    private EquipSettings parseEquip(YamlConfiguration config) {
        double chance = clampDouble(config.getDouble("chance", 0.10D), 0.0D, 1.0D);
        Map<String, Double> worlds = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("worlds");
        if (section != null) {
            for (String world : section.getKeys(false)) {
                double value = section.getDouble(world, chance);
                if (value < 0.0D || value > 1.0D) {
                    plugin.getLogger().warning(EQUIP_FILE + "/worlds." + world
                            + " 概率超出 0..1，已截断");
                }
                worlds.put(world, clampDouble(value, 0.0D, 1.0D));
            }
        }
        return new EquipSettings(chance, Map.copyOf(worlds));
    }

    /**
     * 解析 {@code update.yml}：检查更新的开关、仓库地址与提醒方式。
     *
     * <p>仓库地址写得不合法（不是 {@code owner/repo}）时回退到内置默认值，
     * 免得每几小时对着一个必然 404 的地址发请求。</p>
     */
    private UpdateSettings parseUpdate(YamlConfiguration config) {
        UpdateSettings defaults = UpdateSettings.defaults();
        if (config == null) {
            return defaults;
        }
        boolean enabled = config.getBoolean("enabled", defaults.enabled());
        String repository = config.getString("repository", defaults.repository());
        if (repository == null) {
            repository = defaults.repository();
        }
        repository = repository.trim()
                .replace("https://github.com/", "")
                .replace("http://github.com/", "")
                .replaceAll("\\.git$", "");
        if (repository.indexOf('/') <= 0 || repository.endsWith("/")) {
            plugin.getLogger().warning(UPDATE_FILE + "：repository 需要写成 owner/repo，已回退到 "
                    + defaults.repository());
            repository = defaults.repository();
        }
        int firstDelay = clampInt(config.getInt("first-delay-seconds", defaults.firstDelaySeconds()), 0, 3600);
        int intervalHours = clampInt(config.getInt("interval-hours", defaults.intervalHours()), 0, 24 * 14);
        boolean console = config.getBoolean("console", defaults.console());
        boolean notifyOnline = config.getBoolean("notify-online-admins", defaults.notifyOnlineAdmins());
        boolean notifyJoin = config.getBoolean("notify-on-join", defaults.notifyOnJoin());
        boolean prerelease = config.getBoolean("include-prerelease", defaults.includePrerelease());
        int timeout = clampInt(config.getInt("timeout-seconds", defaults.timeoutSeconds()), 1, 60);
        boolean debug = config.getBoolean("debug", defaults.debug());
        return new UpdateSettings(enabled, repository, firstDelay, intervalHours, console, notifyOnline,
                notifyJoin, prerelease, timeout, debug);
    }

    /**
     * 重新读取配置文件（mining / placement / equip）。
     *
     * @return 新的配置快照
     */
    public Settings reload() {
        YamlConfiguration mining = load(MINING_FILE);
        YamlConfiguration placement = load(PLACEMENT_FILE);
        YamlConfiguration equip = load(EQUIP_FILE);
        YamlConfiguration targeting = load(TARGETING_FILE);
        YamlConfiguration update = load(UPDATE_FILE);
        Settings snapshot = new Settings(parseMining(mining), parsePlacement(placement), parseEquip(equip),
                parseTargeting(targeting), parseUpdate(update));
        this.settings = snapshot;
        return snapshot;
    }

    private YamlConfiguration load(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("读取 " + name + " 失败，使用内置默认值：" + exception.getMessage());
            try {
                configuration.loadFromString(readBundled(name));
            } catch (InvalidConfigurationException ignored) {
                // 内置文件自身损坏时保持空配置，下面的解析会全部走默认值。
            }
        }
        return configuration;
    }

    private String readBundled(String name) {
        try (var stream = plugin.getResource(name)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // mining.yml
    // ------------------------------------------------------------------

    private MiningSettings parseMining(YamlConfiguration config) {
        boolean enabled = config.getBoolean("enabled", true);
        MobFilter mobFilter = parseMobFilter(config, MINING_FILE);
        WorldFilter worldFilter = parseWorldFilter(config, MINING_FILE);
        double maxDistance = clampDouble(config.getDouble("max-distance", 4.5D), 0.5D, 32.0D);

        double multiplier = clampDouble(config.getDouble("break-time.multiplier", 4.0D), 0.01D, 10_000.0D);
        double minSeconds = clampDouble(config.getDouble("break-time.min-seconds", 0.2D), 0.0D, 600.0D);
        double hardCapSeconds = clampDouble(config.getDouble("break-time.hard-cap-seconds", 180.0D), 0.05D, 3600.0D);
        int tickInterval = clampInt(config.getInt("break-time.tick-interval", 2), 1, 20);

        Set<Material> disabled = parseMaterials(config.getStringList("materials.disabled"), MINING_FILE + "/materials.disabled");
        Set<Material> allowed = parseMaterials(config.getStringList("materials.allowed"), MINING_FILE + "/materials.allowed");
        Map<Material, Double> overrides = new EnumMap<>(Material.class);
        ConfigurationSection multiplierSection = config.getConfigurationSection("materials.multiplier");
        if (multiplierSection != null) {
            for (String key : multiplierSection.getKeys(false)) {
                Material material = parseMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning(MINING_FILE + "/materials.multiplier 忽略未知方块：" + key);
                    continue;
                }
                double value = multiplierSection.getDouble(key, multiplier);
                overrides.put(material, clampDouble(value, 0.01D, 10_000.0D));
            }
        }

        boolean triggerPathBlocked = config.getBoolean("trigger.path-blocked", true);
        boolean triggerConfigured = config.getBoolean("trigger.configured-blocks.enabled", false);
        Set<Material> configuredBlocks = parseMaterials(
                config.getStringList("trigger.configured-blocks.blocks"),
                MINING_FILE + "/trigger.configured-blocks.blocks");

        boolean progressViewers = config.getBoolean("feedback.progress-viewers", true);
        double progressRadius = clampDouble(config.getDouble("feedback.progress-radius", 32.0D), 1.0D, 128.0D);
        boolean particles = config.getBoolean("feedback.particles", true);
        Particle particle = parseParticle(config.getString("feedback.particle", "BLOCK"));
        int particleCount = clampInt(config.getInt("feedback.particle-count", 4), 1, 64);
        boolean sounds = config.getBoolean("feedback.sounds", true);
        Sound hitSound = parseSound(config.getString("feedback.hit-sound", "BLOCK_STONE_HIT"), Sound.BLOCK_STONE_HIT);
        Sound breakSound = parseSound(config.getString("feedback.break-sound", "BLOCK_STONE_BREAK"), Sound.BLOCK_STONE_BREAK);
        int soundInterval = clampInt(config.getInt("feedback.sound-interval", 10), 1, 200);

        long cooldownMillis = (long) Math.round(
                clampDouble(config.getDouble("cooldown-seconds", 8.0D), 0.0D, 3600.0D) * 1000.0D);
        boolean dropItems = config.getBoolean("drop-items", false);
        boolean disallowTileEntities = config.getBoolean("safety.disallow-tile-entities", true);
        boolean debug = config.getBoolean("debug", false);

        // 工具相关
        boolean requirePickaxe = config.getBoolean("tool.require-pickaxe", true);
        double handSpeed = clampDouble(config.getDouble("tool.hand-speed", 0.5D), 0.01D, 100.0D);
        Map<Material, Double> pickaxeSpeeds = parsePickaxeSpeeds(config);
        boolean givePickaxe = config.getBoolean("tool.give.enabled", true);
        double givePickaxeChance = clampDouble(config.getDouble("tool.give.chance", 0.8D), 0.0D, 1.0D);
        Map<Material, Integer> pickaxeWeights = parsePickaxeWeights(config);
        double pickaxeDropChance = clampDouble(config.getDouble("tool.give.drop-chance", 0.0D), 0.0D, 1.0D);

        // 目标脚下挖掘 / 头顶挖掘 / 靠近策略 / 重试冷却
        boolean digUnderTarget = config.getBoolean("trigger.dig-under-target", true);
        boolean triggerCeilingBlocked = config.getBoolean("trigger.ceiling-blocked", true);
        boolean triggerDigBelowSelf = config.getBoolean("trigger.dig-below-self", true);
        boolean requireAdjacent = config.getBoolean("require-adjacent", true);
        boolean protectMobBlocks = config.getBoolean("protect-mob-blocks", true);
        double startDistance = clampDouble(config.getDouble("approach.start-distance", 2.5D), 0.5D, 32.0D);
        long retryCooldownMillis = (long) Math.round(
                clampDouble(config.getDouble("retry-seconds", 1.5D), 0.0D, 60.0D) * 1000.0D);

        return new MiningSettings(enabled, mobFilter, worldFilter, maxDistance, multiplier, minSeconds, hardCapSeconds,
                tickInterval, Collections.unmodifiableSet(disabled), Collections.unmodifiableMap(overrides),
                Collections.unmodifiableSet(allowed), triggerPathBlocked, triggerConfigured,
                Collections.unmodifiableSet(configuredBlocks), progressViewers, progressRadius, particles,
                particle, particleCount, sounds, hitSound, breakSound, soundInterval, cooldownMillis,
                dropItems, disallowTileEntities, debug, requirePickaxe, handSpeed,
                Collections.unmodifiableMap(pickaxeSpeeds), givePickaxe, givePickaxeChance,
                Collections.unmodifiableMap(pickaxeWeights), pickaxeDropChance,
                digUnderTarget, triggerCeilingBlocked, triggerDigBelowSelf, retryCooldownMillis,
                requireAdjacent, protectMobBlocks);
    }

    /** 镐子材质 → 速度系数；未配置时使用内置默认（木镐 = 1.0 基准）。 */
    private Map<Material, Double> parsePickaxeSpeeds(YamlConfiguration config) {
        Map<Material, Double> speeds = new EnumMap<>(Material.class);
        speeds.put(Material.WOODEN_PICKAXE, 1.0D);
        ConfigurationSection section = config.getConfigurationSection("tool.speed");
        if (section == null) {
            return speeds;
        }
        for (String key : section.getKeys(false)) {
            Material material = parseMaterial(key);
            if (material == null || !isPickaxeMaterial(material)) {
                plugin.getLogger().warning(MINING_FILE + "/tool.speed 忽略非镐子条目：" + key);
                continue;
            }
            double value = clampDouble(section.getDouble(key, 1.0D), 0.01D, 1000.0D);
            speeds.put(material, value);
        }
        return speeds;
    }

    /** 自带镐子的权重表；留空时使用内置默认。 */
    private Map<Material, Integer> parsePickaxeWeights(YamlConfiguration config) {
        Map<Material, Integer> weights = new EnumMap<>(Material.class);
        ConfigurationSection section = config.getConfigurationSection("tool.give.materials");
        if (section == null) {
            weights.put(Material.WOODEN_PICKAXE, 60);
            weights.put(Material.STONE_PICKAXE, 25);
            weights.put(Material.IRON_PICKAXE, 12);
            weights.put(Material.DIAMOND_PICKAXE, 3);
            return weights;
        }
        for (String key : section.getKeys(false)) {
            Material material = parseMaterial(key);
            if (material == null || !isPickaxeMaterial(material)) {
                plugin.getLogger().warning(MINING_FILE + "/tool.give.materials 忽略非镐子条目：" + key);
                continue;
            }
            int weight = clampInt(section.getInt(key, 0), 0, 100_000);
            if (weight > 0) {
                weights.put(material, weight);
            }
        }
        if (weights.isEmpty()) {
            plugin.getLogger().warning(MINING_FILE + "/tool.give.materials 没有有效条目，怪物将不会自带镐子");
        }
        return weights;
    }

    private static boolean isPickaxeMaterial(Material material) {
        return material.isItem() && material.name().endsWith("_PICKAXE");
    }

    // ------------------------------------------------------------------
    // placement.yml
    // ------------------------------------------------------------------

    private PlacementSettings parsePlacement(YamlConfiguration config) {
        boolean enabled = config.getBoolean("enabled", true);
        MobFilter mobFilter = parseMobFilter(config, PLACEMENT_FILE);
        WorldFilter worldFilter = parseWorldFilter(config, PLACEMENT_FILE);

        double chance = clampDouble(config.getDouble("backpack.chance", 0.65D), 0.0D, 1.0D);
        int totalMin = clampInt(config.getInt("backpack.total.min", 6), 0, 256);
        int totalMax = clampInt(config.getInt("backpack.total.max", 20), 0, 256);
        if (totalMax < totalMin) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：backpack.total.max 小于 min，已交换两者");
            int swap = totalMin;
            totalMin = totalMax;
            totalMax = swap;
        }
        int kindsMin = clampInt(config.getInt("backpack.kinds.min", 1), 0, 64);
        int kindsMax = clampInt(config.getInt("backpack.kinds.max", 4), 0, 64);
        if (kindsMax < kindsMin) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：backpack.kinds.max 小于 min，已交换两者");
            int swap = kindsMin;
            kindsMin = kindsMax;
            kindsMax = swap;
        }

        List<PlacementEntry> entries = parseEntries(config);

        double maxDistance = clampDouble(config.getDouble("placement.max-distance", 4.0D), 0.5D, 32.0D);
        int maxPerChase = clampInt(config.getInt("placement.max-per-chase", DEFAULT_MAX_PER_CHASE), 0, 512);
        // 旧版配置迁移：v1 的默认值是 10，比背包总量（默认最多 20）还小，
        // 结果怪物会在桥 / 柱子搭到一半时“突然停手”，看起来像插件坏了。
        if (config.getInt("config-version", 1) < CONFIG_VERSION && maxPerChase == LEGACY_MAX_PER_CHASE) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：检测到旧版 placement.max-per-chase: "
                    + LEGACY_MAX_PER_CHASE + "，已按新版默认值 " + DEFAULT_MAX_PER_CHASE + " 运行。"
                    + "建议把这一项手动改大（或删除该文件重新生成），否则搭路会在中途停手。");
            maxPerChase = DEFAULT_MAX_PER_CHASE;
        }
        long cooldownMillis = (long) Math.round(
                clampDouble(config.getDouble("placement.cooldown-seconds", 3.0D), 0.0D, 3600.0D) * 1000.0D);
        int buildDelayTicks = clampInt(config.getInt("placement.build-delay-ticks", 6), 0, 200);
        double verticalDrop = clampDouble(config.getDouble("placement.vertical-drop", 1.5D), 0.0D, 64.0D);
        int lifetime = clampInt(config.getInt("placement.block-lifetime-seconds", 15), 0, 86_400);
        boolean pillarUp = config.getBoolean("placement.pillar-up", true);
        int pillarUpMaxHeight = clampInt(config.getInt("placement.pillar-up-max-height", 16), 0, 256);
        boolean stairUp = config.getBoolean("placement.stair-up", true);
        double stairMinDistance = clampDouble(config.getDouble("placement.stair-min-distance", 4.0D), 1.0D, 64.0D);
        int pillarFarHeight = clampInt(config.getInt("placement.pillar-far-height", 8), 1, 256);
        double pillarNearDistance = clampDouble(
                config.getDouble("placement.pillar-near-distance", 2.0D), 0.0D, 64.0D);
        if (pillarNearDistance >= stairMinDistance) {
            double adjusted = Math.max(0.0D, stairMinDistance - 1.0D);
            plugin.getLogger().warning(PLACEMENT_FILE + "：placement.pillar-near-distance 不小于 "
                    + "placement.stair-min-distance，已下调到 " + adjusted + "（否则永远不会搭楼梯）");
            pillarNearDistance = adjusted;
        }
        boolean bridge = config.getBoolean("placement.bridge", true);
        int bridgeMaxLength = clampInt(config.getInt("placement.bridge-max-length", 24), 1, 256);
        int laneSearchRadius = clampInt(config.getInt("placement.lane-search-radius", 3), 0, 8);
        boolean verticalAggro = config.getBoolean("placement.vertical-aggro", true);
        boolean specialOnlyUnderTarget = config.getBoolean("placement.special-only-under-target", true);
        boolean spreadCrowd = config.getBoolean("placement.spread-crowd", true);
        double spacingRadius = clampDouble(config.getDouble("placement.spacing-radius", 1.0D), 0.5D, 8.0D);
        boolean smartAscend = config.getBoolean("placement.smart-ascend", true);
        boolean requireAdjacent = config.getBoolean("placement.require-adjacent", true);
        double maxFacingAngle = clampDouble(config.getDouble("placement.max-facing-angle", 60.0D), 5.0D, 180.0D);
        long retryCooldownMillis = (long) Math.round(
                clampDouble(config.getDouble("placement.retry-seconds", 1.5D), 0.0D, 60.0D) * 1000.0D);

        // 主动拾取地上的物品
        boolean pickupEnabled = config.getBoolean("pickup.enabled", true);
        double pickupRadius = clampDouble(config.getDouble("pickup.radius", 8.0D), 1.0D, 32.0D);
        double pickupReach = clampDouble(config.getDouble("pickup.reach", 1.8D), 0.5D, 6.0D);
        if (pickupReach > pickupRadius) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：pickup.reach 大于 pickup.radius，"
                    + "已调整为 radius 的一半（否则永远捡不到远处的物品）");
            pickupReach = pickupRadius / 2.0D;
        }
        boolean pickupBlocks = config.getBoolean("pickup.blocks", true);
        boolean pickupPickaxes = config.getBoolean("pickup.pickaxes", true);
        // 默认只有通过 equip.yml 闸门的怪物才拾取：
        // 否则捡来的方块 / 镐子会让“带装备”的比例超过 equip.chance。
        boolean pickupOnlyEquipped = config.getBoolean("pickup.only-equipped", true);

        return new PlacementSettings(enabled, mobFilter, worldFilter, chance, totalMin, totalMax, kindsMin, kindsMax,
                List.copyOf(entries), maxDistance, maxPerChase, cooldownMillis, buildDelayTicks, verticalDrop,
                lifetime, stairUp, stairMinDistance, pillarUp, pillarUpMaxHeight, pillarFarHeight,
                pillarNearDistance, bridge, bridgeMaxLength, laneSearchRadius,
                requireAdjacent, maxFacingAngle, verticalAggro,
                specialOnlyUnderTarget, spreadCrowd, spacingRadius, smartAscend,
                retryCooldownMillis, pickupEnabled, pickupRadius, pickupReach, pickupBlocks, pickupPickaxes,
                pickupOnlyEquipped);
    }

    private List<PlacementEntry> parseEntries(YamlConfiguration config) {
        List<PlacementEntry> entries = new ArrayList<>();
        List<Map<?, ?>> raw = config.getMapList("entries");
        Set<String> ids = new LinkedHashSet<>();
        for (Map<?, ?> map : raw) {
            PlacementEntry entry = parseEntry(map);
            if (entry == null) {
                continue;
            }
            if (!ids.add(entry.id())) {
                plugin.getLogger().warning(PLACEMENT_FILE + "：重复的条目 id 已忽略：" + entry.id());
                continue;
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：没有有效的可放置条目，放置行为将不会生效");
        }
        return entries;
    }

    private PlacementEntry parseEntry(Map<?, ?> map) {
        String id = String.valueOf(map.get("id") == null ? "" : map.get("id"));
        if (id.isBlank()) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：条目缺少 id，已忽略");
            return null;
        }
        EntryType type;
        String rawType = String.valueOf(map.get("type") == null ? "block" : map.get("type"));
        try {
            type = EntryType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：条目 " + id + " 的 type 非法：" + rawType);
            return null;
        }

        double weight = map.get("weight") instanceof Number number ? number.doubleValue() : 1.0D;
        if (weight <= 0.0D) {
            plugin.getLogger().warning(PLACEMENT_FILE + "：条目 " + id + " 的 weight 必须大于 0，已忽略");
            return null;
        }

        switch (type) {
            case BLOCK -> {
                Material material = parseMaterial(asString(map.get("material")));
                if (material == null || material.isAir() || !material.isBlock()) {
                    plugin.getLogger().warning(PLACEMENT_FILE + "：条目 " + id + " 的 material 非法，已忽略");
                    return null;
                }
                String blockData = asString(map.get("block-data"));
                return new PlacementEntry(id, type, weight, material, blockData == null ? "" : blockData,
                        0, null, false);
            }
            case TNT -> {
                int fuse = map.get("fuse-ticks") instanceof Number number ? number.intValue() : 80;
                fuse = clampInt(fuse, 1, 20 * 600);
                return new PlacementEntry(id, type, weight, Material.TNT, "", fuse, null, true);
            }
            case FLUID -> {
                FluidKind fluid = FluidKind.parse(asString(map.get("fluid")));
                if (fluid == null) {
                    plugin.getLogger().warning(PLACEMENT_FILE + "：条目 " + id + " 的 fluid 非法，已忽略");
                    return null;
                }
                boolean consume = !(map.get("consume-bucket") instanceof Boolean flag) || flag;
                return new PlacementEntry(id, type, weight, fluid.material(), "", 0, fluid, consume);
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 解析工具
    // ------------------------------------------------------------------

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Set<EntityType> parseEntityTypes(List<String> raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalized = name.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("*") || normalized.equals("ALL") || normalized.equals("ANY")) {
                continue;
            }
            try {
                EntityType type = EntityType.valueOf(normalized);
                if (!Mob.class.isAssignableFrom(entityClassOf(type))) {
                    plugin.getLogger().warning(source + "：" + name
                            + " 不是可挂载 AI 的生物（Mob），已忽略");
                    continue;
                }
                types.add(type);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(source + "：未知的实体类型 " + name);
            }
        }
        return types;
    }

    /** 实体类型是否写了“全部生物”通配符。 */
    private static boolean hasWildcard(List<String> raw) {
        if (raw == null) {
            return false;
        }
        for (String name : raw) {
            if (name == null) {
                continue;
            }
            String normalized = name.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("*") || normalized.equals("ALL") || normalized.equals("ANY")) {
                return true;
            }
        }
        return false;
    }

    /** 解析挖掘/放置的世界筛选规则。 */
    private WorldFilter parseWorldFilter(YamlConfiguration config, String source) {
        Set<String> whitelist = parseWorldNames(config.getStringList("worlds.whitelist"),
                source + "/worlds.whitelist");
        Set<String> blacklist = parseWorldNames(config.getStringList("worlds.blacklist"),
                source + "/worlds.blacklist");
        return new WorldFilter(Collections.unmodifiableSet(whitelist), Collections.unmodifiableSet(blacklist));
    }

    private Set<String> parseWorldNames(List<String> raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                continue;
            }
            names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        if (names.isEmpty()) {
            plugin.getLogger().warning(source + "：没有有效的世界名，已按空处理");
        }
        return names;
    }

    /** 解析挖掘/放置的生物筛选规则。 */
    private MobFilter parseMobFilter(YamlConfiguration config, String source) {
        List<String> rawWhitelist = config.getStringList("mob-whitelist");
        List<String> rawBlacklist = config.getStringList("mob-blacklist");
        Set<EntityType> whitelist = parseEntityTypes(rawWhitelist, source + "/mob-whitelist");
        Set<EntityType> blacklist = parseEntityTypes(rawBlacklist, source + "/mob-blacklist");
        boolean allMobs = hasWildcard(rawWhitelist);
        MobFilter filter = new MobFilter(Collections.unmodifiableSet(whitelist),
                Collections.unmodifiableSet(blacklist), allMobs);
        if (allMobs && !whitelist.isEmpty()) {
            plugin.getLogger().warning(source + "：mob-whitelist 同时包含 \"*\" 和具体类型，"
                    + "\"*\" 优先，具体条目被忽略");
        }
        return filter;
    }

    /** 尽量把 EntityType 映射到实体类；无法映射时返回 Object（按“不是 Mob”处理）。 */
    private static Class<?> entityClassOf(EntityType type) {
        try {
            Class<?> clazz = type.getEntityClass();
            return clazz != null ? clazz : Object.class;
        } catch (Throwable ignored) {
            return Object.class;
        }
    }

    private Set<Material> parseMaterials(List<String> raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Material material = parseMaterial(name);
            if (material == null) {
                plugin.getLogger().warning(source + "：未知的方块 " + name);
                continue;
            }
            materials.add(material);
        }
        return materials;
    }

    private Material parseMaterial(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material material = Material.getMaterial(name.trim().toUpperCase(Locale.ROOT));
        return material != null ? material : Material.getMaterial(name);
    }

    private Particle parseParticle(String name) {
        if (name != null && !name.isBlank()) {
            try {
                return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(MINING_FILE + "：未知的粒子 " + name + "，使用 BLOCK");
            }
        }
        return Particle.BLOCK;
    }

    private Sound parseSound(String name, Sound fallback) {
        if (name != null && !name.isBlank()) {
            try {
                return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(MINING_FILE + "：未知的音效 " + name + "，使用回退值");
            }
        }
        return fallback;
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
