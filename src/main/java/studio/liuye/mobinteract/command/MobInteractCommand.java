package studio.liuye.mobinteract.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import studio.liuye.mobinteract.MobInteractPlugin;
import studio.liuye.mobinteract.backpack.MobBackpack;
import studio.liuye.mobinteract.config.EquipSettings;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.config.MobFilter;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.config.PlacementSettings;
import studio.liuye.mobinteract.config.WorldFilter;
import studio.liuye.mobinteract.message.Messages;
import studio.liuye.mobinteract.mob.MobProfile;

/** {@code /mobinteract <reload|status|inspect>} 管理命令。 */
public final class MobInteractCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "inspect");

    private final MobInteractPlugin plugin;

    public MobInteractCommand(MobInteractPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mobinteract.admin")) {
            send(sender, "command.common.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            case "inspect" -> inspect(sender);
            default -> send(sender, "command.common.usage", "label", label);
        }
        return true;
    }

    /** 配置重载：在全局区域线程解析配置与消息，再切回发送者线程回话。 */
    private void reload(CommandSender sender) {
        plugin.schedulers().global(() -> {
            try {
                plugin.reloadSettings();
                reply(sender, "command.reload.success", "count", plugin.messages().size());
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "配置重载失败", throwable);
                reply(sender, "command.reload.failed");
            }
        });
    }

    private void status(CommandSender sender) {
        MiningSettings mining = plugin.settings().mining();
        PlacementSettings placement = plugin.settings().placement();
        EquipSettings equip = plugin.settings().equip();
        Messages messages = messages();

        send(sender, "command.status.header");
        send(sender, "command.status.world-filter",
                "mining", describeWorlds(mining.worldFilter()),
                "placement", describeWorlds(placement.worldFilter()));
        send(sender, "command.status.mob-filter",
                "mining", describeMobs(mining.mobFilter()),
                "placement", describeMobs(placement.mobFilter()));
        send(sender, "command.status.mining",
                "enabled", enabledState(mining.enabled()),
                "multiplier", round(mining.multiplier()),
                "maxDistance", round(mining.maxDistance()),
                "cooldown", mining.cooldownMillis() / 1000.0D,
                "drop", onOff(mining.dropItems()));
        send(sender, "command.status.trigger",
                "pathBlocked", mining.triggerPathBlocked(),
                "blocks", mining.triggerConfiguredBlocks()
                        ? String.valueOf(mining.configuredBlocks().size())
                        : messages.get("command.common.off-text"));
        send(sender, "command.status.tool",
                "requirePickaxe", mining.requirePickaxe(),
                "giveChance", mining.givePickaxe()
                        ? round(mining.givePickaxeChance())
                        : messages.get("command.common.off-text"),
                "dropChance", round(mining.pickaxeDropChance()),
                "multiplier", round(mining.multiplier()),
                "handSpeed", round(mining.handSpeed()));
        send(sender, "command.status.placement",
                "enabled", enabledState(placement.enabled()),
                "backpackChance", round(placement.backpackChance()),
                "totalMin", placement.backpackTotalMin(),
                "totalMax", placement.backpackTotalMax(),
                "kindsMin", placement.backpackKindsMin(),
                "kindsMax", placement.backpackKindsMax());
        send(sender, "command.status.limits",
                "maxPerChase", placement.maxPerChase(),
                "lifetime", placement.blockLifetimeSeconds() > 0
                        ? messages.get("command.status.lifetime-seconds",
                                "seconds", placement.blockLifetimeSeconds())
                        : messages.get("command.common.forever"),
                "entries", placement.entries().size());
        send(sender, "command.status.strategy.line",
                "stair", placement.stairUp()
                        ? messages.get("command.status.strategy.stair",
                                "distance", round(placement.stairMinDistance()))
                        : messages.get("command.status.strategy.stair-off"),
                "pillar", onOff(placement.pillarUp()),
                "farHeight", placement.pillarFarHeight(),
                "nearDistance", round(placement.pillarNearDistance()),
                "bridge", placement.bridge()
                        ? messages.get("command.status.strategy.bridge", "max", placement.bridgeMaxLength())
                        : messages.get("command.common.off-text"));
        send(sender, "command.status.pickup.line",
                "range", placement.pickupEnabled()
                        ? messages.get("command.status.pickup.range",
                                "radius", round(placement.pickupRadius()),
                                "reach", round(placement.pickupReach()))
                        : messages.get("command.common.off-text"),
                "blocks", onOff(placement.pickupBlocks()),
                "pickaxes", onOff(placement.pickupPickaxes()));
        send(sender, equip.worlds().isEmpty()
                        ? "command.status.equip.global"
                        : "command.status.equip.override",
                "percent", Math.round(equip.chance() * 100.0D),
                "worlds", equip.worlds().size());
        send(sender, "command.status.runtime",
                "mobs", plugin.registry().size(),
                "mining", plugin.miningSessionCount(),
                "pillars", plugin.registry().totalPillarSteps(),
                "tempBlocks", plugin.tempBlocks().size(),
                "protections", plugin.protection().size());
        send(sender, "command.status.blocklocker",
                "state", plugin.protection().hasBlockLocker()
                        ? plugin.messages().get("command.common.yes-text")
                        : plugin.messages().get("command.common.no-text"));
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "command.common.player-only");
            return;
        }
        Mob nearest = findLookedAtMob(player);
        if (nearest == null) {
            send(player, "command.inspect.no-mob");
            return;
        }
        MobProfile profile = plugin.registry().find(nearest.getUniqueId());
        send(player, "command.inspect.header", "mob", nearest.getType());
        if (profile == null) {
            send(player, "command.inspect.no-profile");
            return;
        }
        long now = System.currentTimeMillis();
        send(player, "command.inspect.cooldowns",
                "mining", secondsLeft(profile.miningCooldownUntil(), now),
                "placement", secondsLeft(profile.placementCooldownUntil(), now));
        send(player, "command.inspect.placements", "count", profile.placementsThisChase());
        org.bukkit.Material pickaxe = studio.liuye.mobinteract.mining.MiningTool.heldPickaxe(nearest);
        double speed = studio.liuye.mobinteract.mining.MiningTool.speedFactor(nearest, plugin.settings().mining());
        send(player, "command.inspect.tool",
                "pickaxe", pickaxe == null ? messages().get("command.common.none") : pickaxe.name(),
                "speed", speed <= 0.0D ? messages().get("command.common.unmineable") : round(speed),
                "granted", yesNo(profile.grantedPickaxe()));
        send(player, "command.inspect.status",
                "status", profile.lastStatus().isEmpty()
                        ? messages().get("command.common.none")
                        : profile.lastStatus(),
                "pillarSteps", profile.pillarSteps());
        MobBackpack backpack = profile.backpack();
        if (backpack == null) {
            send(player, "command.inspect.backpack-none",
                    "state", profile.backpackDecided()
                            ? messages().get("command.common.decided")
                            : messages().get("command.common.undecided"));
            return;
        }
        send(player, "command.inspect.backpack", "kinds", backpack.kinds(), "total", backpack.total());
        for (Map.Entry<PlacementEntry, Integer> entry : backpack.snapshot().entrySet()) {
            send(player, "command.inspect.backpack-entry",
                    "id", entry.getKey().id(),
                    "type", entry.getKey().type(),
                    "count", entry.getValue());
        }
    }

    /**
     * 找出玩家视线内（准星所指）的怪物。
     *
     * <p>在 16 格范围内的怪物里，先要求有清晰视线，再按“与视线方向的夹角”排序，
     * 取最接近准星的那只；有多只时就是光标实际指着的那只。</p>
     */
    private Mob findLookedAtMob(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Mob best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(16.0D, 16.0D, 16.0D)) {
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) {
                continue;
            }
            Location center = mob.getLocation().clone().add(0.0D, mob.getHeight() * 0.5D, 0.0D);
            Vector toMob = center.toVector().subtract(eye.toVector());
            double distance = toMob.length();
            if (distance < 0.3D || distance > 16.0D) {
                continue;
            }
            double cosine = direction.dot(toMob.normalize());
            if (cosine < 0.94D) {
                // 约 20° 的锥体之外，视为没有对准。
                continue;
            }
            if (!player.hasLineOfSight(mob)) {
                continue;
            }
            double score = 1.0D - cosine;
            if (score < bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        return best;
    }

    private String secondsLeft(long timestamp, long now) {
        long millis = timestamp - now;
        if (millis <= 0L) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0D);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ------------------------------------------------------------------
    // 文本（全部来自 message.yml）
    // ------------------------------------------------------------------

    private Messages messages() {
        return plugin.messages();
    }

    /** 发送一条消息；message.yml 里写成列表时按顺序逐行发送。 */
    private void send(CommandSender sender, String key, Object... placeholders) {
        for (String line : messages().getList(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    /** 在发送者自己的调度器上回话，避免跨线程写实体状态。 */
    private void reply(CommandSender sender, String key, Object... placeholders) {
        List<String> lines = messages().getList(key, placeholders);
        if (sender instanceof Entity entity) {
            plugin.schedulers().entity(entity, () -> lines.forEach(sender::sendMessage));
            return;
        }
        lines.forEach(sender::sendMessage);
    }

    /** 总开关状态词（开启 / 关闭）。 */
    private String enabledState(boolean value) {
        return messages().get(value ? "command.common.enabled" : "command.common.disabled");
    }

    /** 布尔开关状态词（开 / 关）。 */
    private String onOff(boolean value) {
        return messages().get(value ? "command.common.on-text" : "command.common.off-text");
    }

    /** 是否状态词（是 / 否）。 */
    private String yesNo(boolean value) {
        return messages().get(value ? "command.common.yes-text" : "command.common.no-text");
    }

    /** 世界筛选摘要（“全部世界 / 白名单(n) / 黑名单(n)”取自 message.yml）。 */
    private String describeWorlds(WorldFilter filter) {
        String base = filter.whitelist().isEmpty()
                ? messages().get("command.status.filter.world-all")
                : messages().get("command.status.filter.whitelist", "count", filter.whitelist().size());
        return filter.blacklist().isEmpty()
                ? base
                : base + messages().get("command.status.filter.blacklist-suffix", "count", filter.blacklist().size());
    }

    /** 生物筛选摘要（“全部生物 / 默认人形 / 白名单(n) / 黑名单(n)”取自 message.yml）。 */
    private String describeMobs(MobFilter filter) {
        String base;
        if (filter.allMobs()) {
            base = messages().get("command.status.filter.mob-all");
        } else if (!filter.whitelist().isEmpty()) {
            base = messages().get("command.status.filter.whitelist", "count", filter.whitelist().size());
        } else {
            base = messages().get("command.status.filter.mob-default");
        }
        return filter.blacklist().isEmpty()
                ? base
                : base + messages().get("command.status.filter.blacklist-suffix", "count", filter.blacklist().size());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
