/*
 * Clearlagg - lag reduction for Paper servers
 * Copyright (C) 2026 LucasTHCR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.empireblocks.clearlagg;

import java.io.File;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class Clearlagg extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static Clearlagg instance;
    private FileConfiguration cfg;
    private FileConfiguration lang;
    private String prefix;
    private NamespacedKey neverDespawnKey;
    private BukkitTask autoClearTask;
    private BukkitTask autoKillTask;
    private BukkitTask limiterTask;
    private BukkitTask tpsMeterTask;
    private BukkitTask chunkUnloadTask;
    private final TpsTracker tpsTracker = new TpsTracker();
    private final HaltManager haltManager = new HaltManager();
    private final SamplerManager samplerManager = new SamplerManager();
    private final GcWatcher gcWatcher = new GcWatcher();
    private final ConfirmationManager confirmationManager = new ConfirmationManager();
    private final ModuleAdmin moduleAdmin = new ModuleAdmin();
    private final CooldownTracker generalCooldowns = new CooldownTracker();
    private final UpdateChecker updateChecker = new UpdateChecker();
    private final Map<UUID, Deque<Long>> eggUsage = new ConcurrentHashMap<>();
    private final Map<Long, Integer> redstoneActivityThisInterval = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> memoryViewers = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> performanceViewers = new ConcurrentHashMap<>();
    private volatile int lastClearAmount = 0;
    private boolean placeholderApiHooked = false;

    public static Clearlagg getInstance() {
        return instance;
    }

    public void onEnable() {
        instance = this;
        this.neverDespawnKey = new NamespacedKey(this, "never_despawn");
        this.saveDefaultConfig();
        this.cfg = this.getConfig();
        this.reloadEverything();
        if (this.cfg.getBoolean("settings.startup-banner", true)) {
            this.getLogger().info("======================================================");
            this.getLogger().info(" Clearlagg v" + this.getDescription().getVersion() + " enabled.");
            this.getLogger().info(" Reducing lag through prevention, not brute force.");
            this.getLogger().info("======================================================");
        }

        if (this.cfg.getBoolean("settings.clean-logs-on-startup.enabled", false)) {
            this.cleanOldLogs(this.cfg.getInt("settings.clean-logs-on-startup.older-than-days", 14));
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand command = this.getCommand("lagg");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        this.startSchedulers();
        this.gcWatcher.start(this);
        this.tpsTracker.start(this);
        this.setupMetrics();
        if (this.cfg.getBoolean("placeholderapi.enabled", true) && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new ClearlaggExpansion(this).register();
                this.placeholderApiHooked = true;
                this.getLogger().info("Hooked into PlaceholderAPI.");
            } catch (Throwable ex) {
                this.getLogger().log(Level.WARNING, "Failed to hook into PlaceholderAPI", ex);
            }
        }

        this.broadcastToOps(this.msg("plugin-enabled"));
    }

    public void onDisable() {
        this.stopSchedulers();
        this.gcWatcher.stop();
        this.tpsTracker.stop();
        this.updateChecker.stop();
        this.memoryViewers.values().forEach(BukkitTask::cancel);
        this.memoryViewers.clear();
        this.performanceViewers.values().forEach(BukkitTask::cancel);
        this.performanceViewers.clear();
        this.broadcastToOps(this.msg("plugin-disabled"));
        instance = null;
    }

    private void startSchedulers() {
        this.stopSchedulers();
        int clearInterval = this.cfg.getInt("entity-clear.auto-clear-interval", 0);
        if (this.moduleAdmin.isEnabled("entity-clear") && clearInterval > 0) {
            long ticks = (long)clearInterval * 20L;
            boolean warn = this.cfg.getBoolean("entity-clear.warn-before-clear.enabled", true);
            int warnBefore = this.cfg.getInt("entity-clear.warn-before-clear.warn-seconds-before", 10);
            if (warn && warnBefore > 0 && warnBefore < clearInterval) {
                long warnDelayTicks = (long)(clearInterval - warnBefore) * 20L;
                this.autoClearTask = Bukkit.getScheduler().runTaskTimer(
                        this,
                        () -> {
                            Bukkit.getScheduler().runTaskLater(
                                    this,
                                    () -> this.broadcastToAll(this.msg("auto-clear-warning").replace("{seconds}", String.valueOf(warnBefore))),
                                    warnDelayTicks
                                );
                            Bukkit.getScheduler().runTaskLater(this, () -> this.runEntityClear(Bukkit.getConsoleSender(), true), ticks);
                        },
                        ticks,
                        ticks
                    );
            } else {
                this.autoClearTask = Bukkit.getScheduler().runTaskTimer(this, () -> this.runEntityClear(Bukkit.getConsoleSender(), true), ticks, ticks);
            }
        }

        int killInterval = this.cfg.getInt("kill-mobs.auto-kill-interval", 0);
        if (this.moduleAdmin.isEnabled("kill-mobs") && killInterval > 0) {
            long ticks = (long)killInterval * 20L;
            this.autoKillTask = Bukkit.getScheduler().runTaskTimer(this, () -> this.runKillMobs(Bukkit.getConsoleSender(), true), ticks, ticks);
        }

        if (this.moduleAdmin.isEnabled("limiters") && this.cfg.getBoolean("limiters.enabled", true)) {
            long ticks = Math.max(20L, this.cfg.getLong("limiters.scan-interval-ticks", 100L));
            this.limiterTask = Bukkit.getScheduler().runTaskTimer(this, this::runLimiterScan, ticks, ticks);
        }

        if (this.moduleAdmin.isEnabled("tps-meter") && this.cfg.getBoolean("tps-meter.enabled", true)) {
            long ticks = Math.max(20L, this.cfg.getLong("tps-meter.check-interval-seconds", 10L) * 20L);
            this.tpsMeterTask = Bukkit.getScheduler().runTaskTimer(this, this::evaluateTpsMeterTriggers, ticks, ticks);
        }

        int unloadInterval = this.cfg.getInt("chunk-unloader.auto-unload-interval", 0);
        if (this.moduleAdmin.isEnabled("chunk-unloader") && unloadInterval > 0) {
            long ticks = (long)unloadInterval * 20L;
            this.chunkUnloadTask = Bukkit.getScheduler().runTaskTimer(this, () -> this.runUnloadChunks(Bukkit.getConsoleSender()), ticks, ticks);
        }
    }

    private void stopSchedulers() {
        this.cancelQuietly(this.autoClearTask);
        this.autoClearTask = null;
        this.cancelQuietly(this.autoKillTask);
        this.autoKillTask = null;
        this.cancelQuietly(this.limiterTask);
        this.limiterTask = null;
        this.cancelQuietly(this.tpsMeterTask);
        this.tpsMeterTask = null;
        this.cancelQuietly(this.chunkUnloadTask);
        this.chunkUnloadTask = null;
    }

    private void setupMetrics() {
        int pluginId = 32979;
        Metrics metrics = new Metrics(this, pluginId);
        metrics.addCustomChart(new SimplePie("language", () -> this.cfg.getString("settings.language", "english")));
        metrics.addCustomChart(new SimplePie("adjustment_placeholderapi_hooked", () -> this.placeholderApiHooked ? "yes" : "no"));
        metrics.addCustomChart(new SimplePie("halt_mode_active", () -> this.haltManager.isHalted() ? "yes" : "no"));
    }

    private void cancelQuietly(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {
                // Task was never scheduled or the scheduler is already down.
            }
        }
    }

    public void reloadEverything() {
        this.reloadConfig();
        this.cfg = this.getConfig();
        this.prefix = ChatColor.translateAlternateColorCodes('&', this.cfg.getString("settings.prefix", "&8[&bClearlagg&8] &7"));
        this.loadLanguage();
        this.moduleAdmin.load(this.cfg);
        this.startSchedulers();
        this.updateChecker.start(this);
    }

    private void loadLanguage() {
        String requested = this.cfg.getString("settings.language", "english");
        String normalized = this.normalizeLanguageKey(requested);
        File langDir = new File(this.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        String[] supported = new String[]{
            "english",
            "german",
            "french",
            "spanish",
            "russian",
            "polish",
            "czech",
            "japanese",
            "korean",
            "brazilianportuguese",
            "chinesesimplified",
            "chinesetraditional"
        };

        for (String key : supported) {
            File target = new File(langDir, "messages_" + key + ".yml");
            if (!target.exists()) {
                try {
                    this.saveResource("lang/messages_" + key + ".yml", false);
                } catch (IllegalArgumentException ignored) {
                    // Language file isn't bundled in this build; skip it.
                }
            }
        }

        File langFile = new File(langDir, "messages_" + normalized + ".yml");
        if (!langFile.exists()) {
            this.getLogger().warning("Language '" + requested + "' not found, falling back to English.");
            langFile = new File(langDir, "messages_english.yml");
        }

        this.lang = YamlConfiguration.loadConfiguration(langFile);
        File englishFile = new File(langDir, "messages_english.yml");
        if (englishFile.exists() && !englishFile.equals(langFile)) {
            YamlConfiguration englishDefaults = YamlConfiguration.loadConfiguration(englishFile);
            ((YamlConfiguration)this.lang).setDefaults(englishDefaults);
        }
    }

    private String normalizeLanguageKey(String raw) {
        if (raw == null) {
            return "english";
        }

        String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        switch (key) {
            case "en":
            case "eng":
            case "english":
                return "english";
            case "de":
            case "ger":
            case "german":
            case "deutsch":
                return "german";
            case "fr":
            case "french":
            case "francais":
                return "french";
            case "es":
            case "spanish":
            case "espanol":
                return "spanish";
            case "ru":
            case "russian":
                return "russian";
            case "pl":
            case "polish":
                return "polish";
            case "cz":
            case "cs":
            case "czech":
                return "czech";
            case "ja":
            case "jp":
            case "japanese":
                return "japanese";
            case "ko":
            case "kr":
            case "korean":
                return "korean";
            case "brazilianportuguese":
            case "ptbr":
            case "pt":
            case "portuguese":
                return "brazilianportuguese";
            case "chinesesimplified":
            case "zhcn":
            case "simplifiedchinese":
                return "chinesesimplified";
            case "chinesetraditional":
            case "zhtw":
            case "traditionalchinese":
                return "chinesetraditional";
            default:
                return "english";
        }
    }

    public String msg(String key) {
        String raw = this.lang != null ? this.lang.getString(key) : null;
        if (raw == null) {
            raw = "&c[Missing message: " + key + "]";
        }

        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String prefixed(String key) {
        return this.prefix + this.msg(key);
    }

    public String getPrefix() {
        return this.prefix;
    }

    private void cleanOldLogs(int olderThanDays) {
        File logsDir = new File(this.getServer().getWorldContainer(), "logs");
        if (logsDir.exists() && logsDir.isDirectory()) {
            File[] files = logsDir.listFiles();
            if (files != null) {
                long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis((long)olderThanDays);
                int removed = 0;

                for (File f : files) {
                    if (f.isFile()
                        && f.lastModified() < cutoff
                        && (f.getName().endsWith(".log") || f.getName().endsWith(".log.gz") || f.getName().endsWith(".gz"))
                        && f.delete()) {
                        removed++;
                    }
                }

                if (removed > 0) {
                    this.getLogger().info("Removed " + removed + " old log file(s) on startup.");
                }
            }
        }
    }

    private void broadcastToAll(String message) {
        Bukkit.broadcastMessage(this.prefix + message);
    }

    private void broadcastToOps(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("lagg.notify")) {
                p.sendMessage(this.prefix + message);
            }
        }

        this.getLogger().info(ChatColor.stripColor(message));
    }

    public int runEntityClear(CommandSender sender, boolean auto) {
        if (!this.moduleAdmin.isEnabled("entity-clear")) {
            return 0;
        }

        Set<String> excludedWorlds = new HashSet<>(this.cfg.getStringList("entity-clear.excluded-worlds"));
        ConfigurationSection entitiesSection = this.cfg.getConfigurationSection("entity-clear.entities");
        Set<EntityType> targets = this.resolveEnabledEntityTypes(entitiesSection);
        boolean ignoreNamed = this.cfg.getBoolean("entity-clear.ignore-named-entities", true);
        boolean ignoreLeashed = this.cfg.getBoolean("entity-clear.ignore-leashed-entities", true);
        boolean ignoreTamed = this.cfg.getBoolean("entity-clear.ignore-tamed-entities", true);
        int removed = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!excludedWorlds.contains(world.getName())) {
                for (Entity entity : world.getEntities()) {
                    if (!(entity instanceof Player)
                        && targets.contains(entity.getType())
                        && (!ignoreNamed || entity.getCustomName() == null)
                        && (!ignoreLeashed || !this.isLeashed(entity))
                        && (!ignoreTamed || !this.isTamed(entity))) {
                        entity.remove();
                        removed++;
                    }
                }
            }
        }

        this.lastClearAmount = removed;
        int minBroadcast = this.cfg.getInt("entity-clear.broadcast-min-amount", 1);
        if (removed >= minBroadcast) {
            String message = this.cfg.getString("entity-clear.broadcast-message", "&aCleared &e{amount} &aentities!")
                .replace("{amount}", String.valueOf(removed));
            this.broadcastToAll(ChatColor.translateAlternateColorCodes('&', message));
        }

        return removed;
    }

    private Set<EntityType> resolveEnabledEntityTypes(ConfigurationSection section) {
        Set<EntityType> types = new HashSet<>();
        if (section == null) {
            return types;
        }

        for (String key : section.getKeys(false)) {
            if (section.getBoolean(key, false)) {
                EntityType type = this.safeEntityType(key);
                if (type != null) {
                    types.add(type);
                }
            }
        }

        return types;
    }

    private EntityType safeEntityType(String name) {
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isLeashed(Entity entity) {
        if (entity instanceof LivingEntity) {
            try {
                return ((LivingEntity)entity).isLeashed();
            } catch (Throwable ex) {
                return false;
            }
        } else {
            return false;
        }
    }

    private boolean isTamed(Entity entity) {
        try {
            if (entity instanceof Tameable) {
                return ((Tameable)entity).isTamed();
            }
        } catch (Throwable ignored) {
            // Some server forks throw on Tameable#isTamed; treat as untamed.
        }

        return false;
    }

    public int runKillMobs(CommandSender sender, boolean auto) {
        if (!this.moduleAdmin.isEnabled("kill-mobs")) {
            return 0;
        }

        Set<String> excludedWorlds = new HashSet<>(this.cfg.getStringList("kill-mobs.excluded-worlds"));
        ConfigurationSection mobsSection = this.cfg.getConfigurationSection("kill-mobs.mobs");
        Set<EntityType> targets = this.resolveEnabledEntityTypes(mobsSection);
        boolean ignoreNamed = this.cfg.getBoolean("kill-mobs.ignore-named-entities", true);
        boolean ignoreLeashed = this.cfg.getBoolean("kill-mobs.ignore-leashed-entities", true);
        boolean ignoreTamed = this.cfg.getBoolean("kill-mobs.ignore-tamed-entities", true);
        int killed = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!excludedWorlds.contains(world.getName())) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof LivingEntity
                        && !(entity instanceof Player)
                        && targets.contains(entity.getType())
                        && (!ignoreNamed || entity.getCustomName() == null)
                        && (!ignoreLeashed || !this.isLeashed(entity))
                        && (!ignoreTamed || !this.isTamed(entity))) {
                        ((LivingEntity)entity).setHealth(0.0);
                        killed++;
                    }
                }
            }
        }

        if (killed > 0) {
            String message = this.cfg.getString("kill-mobs.broadcast-message", "&aKilled &e{amount} &amobs!").replace("{amount}", String.valueOf(killed));
            this.broadcastToAll(ChatColor.translateAlternateColorCodes('&', message));
        }

        return killed;
    }

    public int runAreaClear(Player player, int radius) {
        int removed = 0;
        double radiusSq = (double)radius * (double)radius;
        Location origin = player.getLocation();

        for (Entity entity : player.getWorld().getEntities()) {
            if (!(entity instanceof Player) && entity.getLocation().distanceSquared(origin) <= radiusSq) {
                entity.remove();
                removed++;
            }
        }

        return removed;
    }

    public int runUnloadChunks(CommandSender sender) {
        int safeRadius = this.cfg.getInt("chunk-unloader.player-safe-radius", 4);
        int unloaded = 0;

        for (World world : Bukkit.getWorlds()) {
            Set<Long> safeChunks = new HashSet<>();

            for (Player p : world.getPlayers()) {
                int pcx = p.getLocation().getBlockX() >> 4;
                int pcz = p.getLocation().getBlockZ() >> 4;

                for (int dx = -safeRadius; dx <= safeRadius; dx++) {
                    for (int dz = -safeRadius; dz <= safeRadius; dz++) {
                        safeChunks.add(this.chunkKey(world, pcx + dx, pcz + dz));
                    }
                }
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                if (!safeChunks.contains(this.chunkKey(world, chunk.getX(), chunk.getZ())) && chunk.unload(true)) {
                    unloaded++;
                }
            }
        }

        return unloaded;
    }

    private long chunkKey(World world, int x, int z) {
        return (long)x << 32 ^ (long)z & 4294967295L ^ (long)world.getName().hashCode() << 1;
    }

    private long chunkKeyOf(Chunk chunk) {
        return this.chunkKey(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    private void runLimiterScan() {
        this.redstoneActivityThisInterval.clear();
        boolean entityLimiterOn = this.moduleAdmin.isEnabled("limiters") && this.cfg.getBoolean("limiters.entity-limiter.enabled", true);
        boolean refreshNeverDespawn = this.moduleAdmin.isEnabled("item-livetime") && this.cfg.getBoolean("item-livetime.enabled", true);
        if (entityLimiterOn || refreshNeverDespawn) {
            ConfigurationSection limitsSection = this.cfg.getConfigurationSection("limiters.entity-limiter.per-chunk-limits");
            Map<EntityType, Integer> limits = new EnumMap<>(EntityType.class);
            if (limitsSection != null) {
                for (String key : limitsSection.getKeys(false)) {
                    EntityType type = this.safeEntityType(key);
                    if (type != null) {
                        limits.put(type, limitsSection.getInt(key));
                    }
                }
            }

            int defaultLimit = this.cfg.getInt("limiters.entity-limiter.default-limit", -1);

            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    Map<EntityType, List<Entity>> perType = entityLimiterOn ? new EnumMap<>(EntityType.class) : null;

                    for (Entity entity : chunk.getEntities()) {
                        if (refreshNeverDespawn
                            && entity instanceof Item
                            && entity.getPersistentDataContainer().has(this.neverDespawnKey, PersistentDataType.BYTE)) {
                            entity.setTicksLived(1);
                        }

                        if (entityLimiterOn && !(entity instanceof Player)) {
                            perType.computeIfAbsent(entity.getType(), t -> new ArrayList<>()).add(entity);
                        }
                    }

                    if (entityLimiterOn) {
                        for (Entry<EntityType, List<Entity>> entry : perType.entrySet()) {
                            int limit = limits.getOrDefault(entry.getKey(), defaultLimit);
                            if (limit >= 0) {
                                List<Entity> list = entry.getValue();
                                if (list.size() > limit) {
                                    for (int i = limit; i < list.size(); i++) {
                                        list.get(i).remove();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void evaluateTpsMeterTriggers() {
        List<Map<?, ?>> triggers = this.cfg.getMapList("tps-meter.triggers");
        double tps = this.tpsTracker.getTps1m();
        double usedMb = this.getUsedRamMb();
        double maxMb = this.getMaxRamMb();
        double ramPercent = this.getRamPercent();

        for (Map<?, ?> raw : triggers) {
            boolean enabled = raw.get("enabled") == null || Boolean.TRUE.equals(raw.get("enabled"));
            if (enabled) {
                String name = String.valueOf(raw.get("name"));
                String condition = String.valueOf(raw.get("condition"));
                double value = raw.get("value") instanceof Number ? ((Number)raw.get("value")).doubleValue() : 0.0;

                if (switch (condition) {
                    case "TPS_BELOW" -> tps < value;
                    case "RAM_ABOVE_PERCENT" -> ramPercent > value;
                    default -> false;
                }) {
                    long cooldownSeconds = raw.get("cooldown-seconds") instanceof Number ? ((Number)raw.get("cooldown-seconds")).longValue() : 60L;
                    String cooldownKey = "tpsmeter:" + name;
                    if (!this.generalCooldowns.isOnCooldown(cooldownKey, cooldownSeconds * 1000L)) {
                        this.generalCooldowns.markUsed(cooldownKey);
                        Object commandsObj = raw.get("commands");
                        if (commandsObj instanceof List<?> commands) {
                            for (Object cmdObj : commands) {
                                String cmd = String.valueOf(cmdObj).replace("{tps}", String.format(Locale.ROOT, "%.2f", tps))
                                    .replace("{ram_used}", String.valueOf((long)usedMb)).replace("{ram_max}", String.valueOf((long)maxMb))
                                    .replace("{ram_percent}", String.format(Locale.ROOT, "%.1f", ramPercent));
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                            }
                        }
                    }
                }
            }
        }
    }

    public void onGcEvent(String collectorName, long durationMs) {
        if (this.moduleAdmin.isEnabled("gc-monitor") && this.cfg.getBoolean("gc-monitor.enabled", true)) {
            long warnMs = this.cfg.getLong("gc-monitor.warn-pause-ms", 500L);
            if (durationMs >= warnMs) {
                String message = ChatColor.translateAlternateColorCodes(
                    '&',
                    this.cfg.getString("gc-monitor.message", "&c[Clearlagg] Long GC pause detected: &e{duration}ms &c(collector: {collector})")
                        .replace("{duration}", String.valueOf(durationMs)).replace("{collector}", collectorName)
                );
                this.getLogger().warning(ChatColor.stripColor(message));
                if (this.cfg.getBoolean("gc-monitor.notify-ops-in-game", true)) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission("lagg.notify")) {
                            p.sendMessage(message);
                        }
                    }
                }
            }
        }
    }

    public double getUsedRamMb() {
        Runtime rt = Runtime.getRuntime();
        return (double)(rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0;
    }

    public double getMaxRamMb() {
        return (double)Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0;
    }

    public double getRamPercent() {
        double max = this.getMaxRamMb();
        return max > 0.0 ? this.getUsedRamMb() / max * 100.0 : 0.0;
    }

    public int getTotalEntityCount() {
        int total = 0;

        for (World w : Bukkit.getWorlds()) {
            total += w.getEntities().size();
        }

        return total;
    }

    public int getLastClearAmount() {
        return this.lastClearAmount;
    }

    public String colorizeTps(double tps) {
        String color = tps >= 18.0 ? "&a" : (tps >= 15.0 ? "&e" : "&c");
        return ChatColor.translateAlternateColorCodes('&', color + String.format(Locale.ROOT, "%.2f", tps));
    }

    public void maybeAutoClearFromPlaceholder(String params) {
        if (this.cfg.getBoolean("placeholderapi.auto-clear-on-placeholder-request.enabled", false)) {
            String trigger = this.cfg.getString("placeholderapi.auto-clear-on-placeholder-request.trigger-placeholder", "clearlagg_autoclear");
            if (trigger.equalsIgnoreCase(params)) {
                long cooldownMs = (long)this.cfg.getInt("placeholderapi.auto-clear-on-placeholder-request.cooldown-seconds", 300) * 1000L;
                if (!this.generalCooldowns.isOnCooldown("autoclear", cooldownMs)) {
                    this.generalCooldowns.markUsed("autoclear");
                    Bukkit.getScheduler().runTask(this, () -> this.runEntityClear(Bukkit.getConsoleSender(), true));
                }
            }
        }
    }

    public TpsTracker getTpsTracker() {
        return this.tpsTracker;
    }

    public HaltManager getHaltManager() {
        return this.haltManager;
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onTntSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof TNTPrimed) {
            if (this.moduleAdmin.isEnabled("tnt") && this.cfg.getBoolean("tnt.enabled", true)) {
                int maxPerChunk = this.cfg.getInt("tnt.max-primed-per-chunk", 25);
                if (maxPerChunk > 0) {
                    Chunk chunk = event.getLocation().getChunk();
                    long count = 0L;

                    for (Entity e : chunk.getEntities()) {
                        if (e instanceof TNTPrimed) {
                            count++;
                        }
                    }

                    if (count >= (long)maxPerChunk) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.getEntity() instanceof TNTPrimed) {
            if (this.moduleAdmin.isEnabled("tnt") && this.cfg.getBoolean("tnt.enabled", true)) {
                double radius = this.cfg.getDouble("tnt.chain-reaction-radius-limit", 0.0);
                if (radius > 0.0) {
                    int maxPerChunk = this.cfg.getInt("tnt.max-primed-per-chunk", 25);
                    long nearby = event.getEntity().getNearbyEntities(radius, radius, radius).stream().filter(e -> e instanceof TNTPrimed).count();
                    if (nearby > (long)maxPerChunk) {
                        event.setCancelled(true);
                        event.getEntity().remove();
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof TNTPrimed) {
            if (this.moduleAdmin.isEnabled("tnt") && this.cfg.getBoolean("tnt.enabled", true)) {
                if (this.cfg.getBoolean("tnt.disable-block-damage", false)) {
                    event.blockList().clear();
                } else {
                    int maxBlocks = this.cfg.getInt("tnt.max-blocks-per-explosion", 500);
                    if (maxBlocks > 0 && event.blockList().size() > maxBlocks) {
                        List<Block> blocks = event.blockList();
                        Collections.shuffle(blocks);

                        while (blocks.size() > maxBlocks) {
                            blocks.remove(blocks.size() - 1);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onItemSpawn(ItemSpawnEvent event) {
        if (this.moduleAdmin.isEnabled("item-livetime") && this.cfg.getBoolean("item-livetime.enabled", true)) {
            Item item = event.getEntity();
            Material material = item.getItemStack().getType();
            int seconds = this.cfg.getInt("item-livetime.overrides." + material.name(), this.cfg.getInt("item-livetime.default-seconds", 300));
            if (seconds < 0) {
                item.setPersistent(true);
                item.getPersistentDataContainer().set(this.neverDespawnKey, PersistentDataType.BYTE, (byte)1);
            } else {
                long ticks = (long)seconds * 20L;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (item.isValid()) {
                        item.remove();
                    }
                }, ticks);
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (this.haltManager.isHalted() && this.cfg.getBoolean("halt.disable.mob-spawning", true)) {
            event.setCancelled(true);
        } else if (this.moduleAdmin.isEnabled("limiters") && this.cfg.getBoolean("limiters.mob-limiter.enabled", true)) {
            if (this.cfg.getBoolean("limiters.mob-limiter.prevent-spawns-at-limit", true)) {
                int perChunk = this.cfg.getInt("limiters.mob-limiter.per-chunk-limit", 25);
                Chunk chunk = event.getLocation().getChunk();
                if (perChunk > 0) {
                    long count = Arrays.stream(chunk.getEntities()).filter(e -> e instanceof LivingEntity && !(e instanceof Player)).count();
                    if (count >= (long)perChunk) {
                        event.setCancelled(true);
                        return;
                    }
                }

                int perWorld = this.cfg.getInt("limiters.mob-limiter.per-world-limit", 0);
                if (perWorld > 0) {
                    long worldCount = event.getLocation().getWorld().getEntities().stream()
                        .filter(e -> e instanceof LivingEntity && !(e instanceof Player)).count();
                    if (worldCount >= (long)perWorld) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onEntityBreed(EntityBreedEvent event) {
        if (this.moduleAdmin.isEnabled("breeding-limiter") && this.cfg.getBoolean("breeding-limiter.enabled", true)) {
            if (event.getEntity() instanceof Animals) {
                List<String> appliesTo = this.cfg.getStringList("breeding-limiter.applies-to");
                EntityType type = event.getEntity().getType();
                if (appliesTo.isEmpty() || appliesTo.contains(type.name())) {
                    int max = this.cfg.getInt("breeding-limiter.max-per-chunk", 30);
                    Chunk chunk = event.getEntity().getLocation().getChunk();
                    long count = Arrays.stream(chunk.getEntities()).filter(e -> e.getType() == type).count();
                    if (count >= (long)max) {
                        event.setCancelled(true);
                        if (event.getBreeder() instanceof Player) {
                            String message = this.cfg.getString("breeding-limiter.message-on-deny", "&cToo many animals nearby!")
                                .replace("{limit}", String.valueOf(max));
                            ((Player)event.getBreeder()).sendMessage(this.prefix + ChatColor.translateAlternateColorCodes('&', message));
                        }
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() == Material.SPAWNER) {
            if (this.moduleAdmin.isEnabled("spawner-limiter") && this.cfg.getBoolean("spawner-limiter.enabled", true)) {
                int radius = this.cfg.getInt("spawner-limiter.radius", 16);
                int max = this.cfg.getInt("spawner-limiter.max-spawners-per-radius", 4);
                int count = this.countSpawnersNear(block.getLocation(), radius);
                if (count >= max) {
                    String action = this.cfg.getString("spawner-limiter.action-on-exceed", "PREVENT_PLACE");
                    if (!"DENY_SILENT".equalsIgnoreCase(action)) {
                        String message = this.cfg.getString("spawner-limiter.message-on-deny", "&cYou cannot place more spawners here!")
                            .replace("{limit}", String.valueOf(max)).replace("{radius}", String.valueOf(radius));
                        event.getPlayer().sendMessage(this.prefix + ChatColor.translateAlternateColorCodes('&', message));
                    }

                    event.setCancelled(true);
                } else {
                    if (block.getState() instanceof CreatureSpawner) {
                        CreatureSpawner spawner = (CreatureSpawner)block.getState();
                        int forceDelay = this.cfg.getInt("spawner-limiter.force-spawn-delay", 0);
                        if (forceDelay > 0) {
                            spawner.setDelay(forceDelay);
                        }

                        int maxRange = this.cfg.getInt("spawner-limiter.max-activation-range", 16);
                        spawner.setSpawnRange(maxRange);
                        spawner.update();
                    }
                }
            }
        }
    }

    private int countSpawnersNear(Location center, int radius) {
        int count = 0;
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (world.getBlockAt(cx + x, cy + y, cz + z).getType() == Material.SPAWNER) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            if (this.moduleAdmin.isEnabled("mob-egg-limiter") && this.cfg.getBoolean("mob-egg-limiter.enabled", true)) {
                ItemStack item = event.getItem();
                if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
                    Player player = event.getPlayer();
                    int max = this.cfg.getInt("mob-egg-limiter.world-overrides." + player.getWorld().getName(), this.cfg.getInt("mob-egg-limiter.max-per-player", 10));
                    long cooldownMs = (long)this.cfg.getInt("mob-egg-limiter.cooldown-seconds", 60) * 1000L;
                    Deque<Long> timestamps = this.eggUsage.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
                    long now = System.currentTimeMillis();

                    while (!timestamps.isEmpty() && now - timestamps.peekFirst() > cooldownMs) {
                        timestamps.pollFirst();
                    }

                    if (timestamps.size() >= max) {
                        long remaining = (cooldownMs - (now - timestamps.peekFirst())) / 1000L;
                        String message = this.cfg.getString("mob-egg-limiter.message-on-deny", "&cYou've reached the mob egg spawn limit! Try again in {seconds}s.")
                            .replace("{seconds}", String.valueOf(Math.max(0L, remaining)));
                        player.sendMessage(this.prefix + ChatColor.translateAlternateColorCodes('&', message));
                        event.setCancelled(true);
                    } else {
                        timestamps.addLast(now);
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (this.haltManager.isHalted() && this.cfg.getBoolean("halt.disable.hopper-transfer", true)) {
            Location destLoc = event.getDestination().getLocation();
            if (destLoc != null && destLoc.getBlock().getType() == Material.HOPPER) {
                event.setCancelled(true);
                return;
            }
        }

        if (this.moduleAdmin.isEnabled("limiters") && this.cfg.getBoolean("limiters.hopper-limiter.enabled", false)) {
            Location loc = event.getDestination().getLocation();
            if (loc != null) {
                Block block = loc.getBlock();
                if (block.getType() == Material.HOPPER) {
                    int limit = this.cfg.getInt("limiters.hopper-limiter.per-chunk-limit", 8);
                    long count = Arrays.stream(block.getChunk().getTileEntities()).filter(te -> te.getType() == Material.HOPPER).count();
                    if (count > (long)limit) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBlockRedstone(BlockRedstoneEvent event) {
        if (this.haltManager.isHalted() && this.cfg.getBoolean("halt.disable.redstone", false)) {
            event.setNewCurrent(event.getOldCurrent());
        } else if (this.moduleAdmin.isEnabled("limiters") && this.cfg.getBoolean("limiters.redstone-limiter.enabled", false)) {
            int limit = this.cfg.getInt("limiters.redstone-limiter.per-chunk-limit", 200);
            Chunk chunk = event.getBlock().getChunk();
            int count = this.redstoneActivityThisInterval.merge(this.chunkKeyOf(chunk), 1, Integer::sum);
            if (count > limit) {
                event.setNewCurrent(event.getOldCurrent());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String key = event.getPlayer().getName();
        BukkitTask memoryTask = this.memoryViewers.remove(key);
        if (memoryTask != null) {
            memoryTask.cancel();
        }

        BukkitTask performanceTask = this.performanceViewers.remove(key);
        if (performanceTask != null) {
            performanceTask.cancel();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("lagg.notify") && this.updateChecker.isUpdateAvailable()) {
            player.sendMessage(
                this.prefix
                    + ChatColor.translateAlternateColorCodes(
                        '&',
                        "&eA new Clearlagg version is available: &a" + this.updateChecker.getLatestVersion() + " &7- &bhttps://modrinth.com/project/zASITjNd"
                    )
            );
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onItemMerge(ItemMergeEvent event) {
        if (this.haltManager.isHalted() && this.cfg.getBoolean("halt.disable.item-merging", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
        ignoreCancelled = true
    )
    public void onBlockFromTo(BlockFromToEvent event) {
        if (this.haltManager.isHalted() && this.cfg.getBoolean("halt.disable.fluid-flow", false)) {
            event.setCancelled(true);
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "clear":
                return this.handleClear(sender);
            case "check":
                return this.handleCheck(sender, rest);
            case "reload":
                return this.handleReload(sender);
            case "killmobs":
                return this.handleKillMobs(sender);
            case "area":
                return this.handleArea(sender, rest);
            case "tpchunk":
                return this.handleTpChunk(sender, rest);
            case "admin":
                return this.handleAdmin(sender, rest);
            case "gc":
                return this.handleGc(sender);
            case "tps":
                return this.handleTps(sender);
            case "halt":
                return this.handleHalt(sender);
            case "samplememory":
                return this.handleSampleMemory(sender, rest);
            case "sampleticks":
                return this.handleSampleTicks(sender, rest);
            case "unloadchunks":
                return this.handleUnloadChunks(sender);
            case "profile":
                return this.handleProfile(sender, rest);
            case "memory":
                return this.handleMemory(sender);
            case "performance":
                return this.handlePerformance(sender);
            case "help":
                this.sendHelp(sender);
                return true;
            default:
                sender.sendMessage(this.prefixed("unknown-command"));
                return true;
        }
    }

    private boolean requirePerm(CommandSender sender, String perm) {
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(this.prefixed("no-permission"));
            return false;
        }

        return true;
    }

    private boolean requireConfirmation(CommandSender sender, String subcommand) {
        if (!this.cfg.getBoolean("commands.confirm-destructive-commands", true)) {
            return true;
        }

        List<String> destructive = this.cfg.getStringList("commands.destructive-commands");
        if (!destructive.contains(subcommand)) {
            return true;
        }

        int window = this.cfg.getInt("commands.confirmation-window-seconds", 10);
        String key = sender.getName() + ":" + subcommand;
        if (this.confirmationManager.confirm(key, (long)window * 1000L)) {
            return true;
        }

        sender.sendMessage(this.prefixed("confirm-required").replace("{seconds}", String.valueOf(window)));
        return false;
    }

    private boolean handleClear(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.clear")) {
            return true;
        }

        if (!this.requireConfirmation(sender, "clear")) {
            return true;
        }

        int removed = this.runEntityClear(sender, false);
        sender.sendMessage(this.prefixed("clear-done").replace("{amount}", String.valueOf(removed)));
        return true;
    }

    private boolean handleKillMobs(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.killmobs")) {
            return true;
        }

        if (!this.requireConfirmation(sender, "killmobs")) {
            return true;
        }

        int killed = this.runKillMobs(sender, false);
        sender.sendMessage(this.prefixed("killmobs-done").replace("{amount}", String.valueOf(killed)));
        return true;
    }

    private boolean handleArea(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.area")) {
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(this.prefixed("player-only"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(this.prefixed("usage-area"));
            return true;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(this.prefixed("invalid-number"));
            return true;
        }

        if (!this.requireConfirmation(sender, "area")) {
            return true;
        }

        int removed = this.runAreaClear((Player)sender, radius);
        sender.sendMessage(this.prefixed("area-done").replace("{amount}", String.valueOf(removed)).replace("{radius}", String.valueOf(radius)));
        return true;
    }

    private boolean handleTpChunk(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.tpchunk")) {
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(this.prefixed("player-only"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(this.prefixed("usage-tpchunk"));
            return true;
        }

        Player player = (Player)sender;

        try {
            int cx = Integer.parseInt(args[0]);
            int cz = Integer.parseInt(args[1]);
            World world = args.length >= 3 ? Bukkit.getWorld(args[2]) : player.getWorld();
            if (world == null) {
                sender.sendMessage(this.prefixed("world-not-found"));
                return true;
            }

            int bx = cx * 16 + 8;
            int bz = cz * 16 + 8;
            Location loc = new Location(world, (double)bx, (double)(world.getHighestBlockYAt(bx, bz) + 1), (double)bz);
            player.teleport(loc);
            sender.sendMessage(this.prefixed("tpchunk-done").replace("{x}", args[0]).replace("{z}", args[1]).replace("{world}", world.getName()));
        } catch (NumberFormatException ex) {
            sender.sendMessage(this.prefixed("tpchunk-invalid"));
        }

        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.admin")) {
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(this.prefixed("usage-admin"));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            sender.sendMessage(this.prefixed("admin-header"));

            for (String module : ModuleAdmin.moduleNames()) {
                boolean enabled = this.moduleAdmin.isEnabled(module);
                sender.sendMessage(
                    ChatColor.translateAlternateColorCodes('&', (enabled ? "&a" : "&c") + module + " &7- " + (enabled ? "enabled" : "disabled"))
                );
            }

            return true;
        }

        if ((action.equals("enable") || action.equals("disable")) && args.length >= 2) {
            String module = args[1];
            this.moduleAdmin.setEnabled(module, action.equals("enable"));
            sender.sendMessage(this.prefixed(action.equals("enable") ? "module-enabled" : "module-disabled").replace("{module}", module));
            return true;
        }

        sender.sendMessage(this.prefixed("usage-admin"));
        return true;
    }

    private boolean handleGc(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.gc")) {
            return true;
        }

        if (!this.requireConfirmation(sender, "gc")) {
            return true;
        }

        long before = (long)this.getUsedRamMb();
        sender.sendMessage(this.prefixed("gc-requested"));
        System.gc();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            long after = (long)this.getUsedRamMb();
            long freed = Math.max(0L, before - after);
            sender.sendMessage(this.prefixed("gc-done").replace("{freed}", String.valueOf(freed)));
        }, 20L);
        return true;
    }

    private boolean handleTps(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.tps")) {
            return true;
        }

        double tps = this.tpsTracker.getTps1m();
        String color = tps >= 18.0 ? "&a" : (tps >= 15.0 ? "&e" : "&c");
        sender.sendMessage(
            this.prefixed("tps-display").replace("{color}", ChatColor.translateAlternateColorCodes('&', color))
                .replace("{tps}", String.format(Locale.ROOT, "%.2f", tps))
        );
        return true;
    }

    private boolean handleHalt(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.halt")) {
            return true;
        }

        boolean newState = !this.haltManager.isHalted();
        this.haltManager.setHalted(newState);
        String message = newState
            ? this.cfg.getString("halt.broadcast-message", this.msg("halt-enabled"))
            : this.cfg.getString("halt.resume-message", this.msg("halt-disabled"));
        this.broadcastToAll(ChatColor.translateAlternateColorCodes('&', message));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.reload")) {
            return true;
        }

        this.reloadEverything();
        sender.sendMessage(this.prefixed("reload-success"));
        return true;
    }

    private boolean handleUnloadChunks(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.unloadchunks")) {
            return true;
        }

        if (!this.requireConfirmation(sender, "unloadchunks")) {
            return true;
        }

        int unloaded = this.runUnloadChunks(sender);
        sender.sendMessage(this.prefixed("unloadchunks-done").replace("{amount}", String.valueOf(unloaded)));
        return true;
    }

    private boolean handleCheck(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.check")) {
            return true;
        }

        List<World> worlds = new ArrayList<>();
        if (args.length == 0) {
            worlds.addAll(Bukkit.getWorlds());
        } else {
            for (String name : args) {
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    worlds.add(w);
                }
            }
        }

        sender.sendMessage(this.prefixed("check-header"));

        for (World world : worlds) {
            int entities = world.getEntities().size();
            int chunks = world.getLoadedChunks().length;
            int tileEntities = 0;

            for (Chunk c : world.getLoadedChunks()) {
                tileEntities += c.getTileEntities().length;
            }

            sender.sendMessage(
                ChatColor.translateAlternateColorCodes(
                    '&', "&b" + world.getName() + "&7: &fentities=&e" + entities + " &7loaded-chunks=&e" + chunks + " &7tile-entities=&e" + tileEntities
                )
            );
        }

        int perPage = this.cfg.getInt("chunk-analysis.results-per-page", 10);
        List<Entry<Chunk, Double>> top = this.topOvercrowdedChunks(worlds);
        sender.sendMessage(this.prefixed("check-overcrowded-header"));
        int shown = 0;

        for (Entry<Chunk, Double> entry : top) {
            if (shown++ >= perPage) {
                break;
            }

            Chunk c = entry.getKey();
            sender.sendMessage(
                ChatColor.translateAlternateColorCodes(
                    '&',
                    "&7- &f"
                        + c.getWorld().getName()
                        + " ("
                        + c.getX()
                        + ", "
                        + c.getZ()
                        + ") &7score=&e"
                        + String.format(Locale.ROOT, "%.1f", entry.getValue())
                )
            );
        }

        return true;
    }

    private List<Entry<Chunk, Double>> topOvercrowdedChunks(List<World> worlds) {
        double entityWeight = this.cfg.getDouble("chunk-analysis.score-weights.entity", 1.0);
        double tileWeight = this.cfg.getDouble("chunk-analysis.score-weights.tile-entity", 1.5);
        Map<Chunk, Double> scores = new HashMap<>();

        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                double score = (double)chunk.getEntities().length * entityWeight + (double)chunk.getTileEntities().length * tileWeight;
                if (score > 0.0) {
                    scores.put(chunk, score);
                }
            }
        }

        List<Entry<Chunk, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list;
    }

    private boolean handleSampleMemory(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.samplememory")) {
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(this.prefixed("usage-samplememory"));
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(this.prefixed("invalid-number"));
            return true;
        }

        if (this.samplerManager.isMemorySampling()) {
            sender.sendMessage(this.prefixed("sample-already-running"));
            return true;
        }

        int maxSeconds = this.cfg.getInt("memory-sampler.max-sample-seconds", 600);
        seconds = Math.min(Math.max(1, seconds), maxSeconds);
        int samplesPerSecond = Math.max(1, this.cfg.getInt("memory-sampler.samples-per-second", 2));
        sender.sendMessage(this.prefixed("sample-started").replace("{duration}", seconds + "s"));
        this.samplerManager.sampleMemory(
                this,
                seconds,
                samplesPerSecond,
                result -> sender.sendMessage(
                        this.prefixed("memory-sample-result").replace("{min}", String.valueOf(result.minMb))
                            .replace("{max}", String.valueOf(result.maxMb))
                            .replace("{avg}", String.format(Locale.ROOT, "%.1f", result.avgMb))
                            .replace("{gccount}", String.valueOf(result.gcCount)).replace("{gctime}", String.valueOf(result.gcTimeMs))
                    )
            );
        return true;
    }

    private boolean handleSampleTicks(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.sampleticks")) {
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(this.prefixed("usage-sampleticks"));
            return true;
        }

        int requested;
        try {
            requested = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(this.prefixed("invalid-number"));
            return true;
        }

        boolean raw = args.length >= 2 && args[1].equalsIgnoreCase("raw");
        if (this.samplerManager.isTickSampling()) {
            sender.sendMessage(this.prefixed("sample-already-running"));
            return true;
        }

        int max = this.cfg.getInt("tick-sampler.max-sample-ticks", 12000);
        int ticks = Math.min(Math.max(1, requested), max);
        double spikeThreshold = this.cfg.getDouble("tick-sampler.spike-threshold-ms", 50.0);
        sender.sendMessage(this.prefixed("sample-started").replace("{duration}", ticks + " ticks"));
        this.samplerManager.sampleTicks(
                this,
                ticks,
                raw,
                samples -> {
                    double totalMs = 0.0;
                    double minMs = Double.MAX_VALUE;
                    double maxMs = 0.0;
                    int spikes = 0;
                    StringBuilder rawOutput = new StringBuilder();

                    for (long ns : samples) {
                        double ms = (double)ns / 1000000.0;
                        totalMs += ms;
                        minMs = Math.min(minMs, ms);
                        maxMs = Math.max(maxMs, ms);
                        if (ms > spikeThreshold) {
                            spikes++;
                        }

                        if (raw) {
                            rawOutput.append(String.format(Locale.ROOT, "%.1f, ", ms));
                        }
                    }

                    double avgMs = samples.isEmpty() ? 0.0 : totalMs / (double)samples.size();
                    sender.sendMessage(
                        this.prefixed("tick-sample-result").replace("{count}", String.valueOf(ticks))
                            .replace("{min}", String.format(Locale.ROOT, "%.1f", minMs == Double.MAX_VALUE ? 0.0 : minMs))
                            .replace("{max}", String.format(Locale.ROOT, "%.1f", maxMs))
                            .replace("{avg}", String.format(Locale.ROOT, "%.2f", avgMs)).replace("{spikes}", String.valueOf(spikes))
                    );
                    if (raw && rawOutput.length() > 0) {
                        sender.sendMessage(ChatColor.GRAY + rawOutput.substring(0, Math.min(rawOutput.length(), 2000)));
                    }
                }
            );
        return true;
    }

    private boolean handleProfile(CommandSender sender, String[] args) {
        if (!this.requirePerm(sender, "lagg.profile")) {
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(this.prefixed("usage-profile"));
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(this.prefixed("invalid-number"));
            return true;
        }

        String type = args[1].toLowerCase(Locale.ROOT);
        List<String> validTypes = this.cfg.getStringList("profiler.types");
        if (!validTypes.contains(type)) {
            sender.sendMessage(this.prefixed("invalid-profile-type").replace("{types}", String.join(", ", validTypes)));
            return true;
        }

        int maxSeconds = this.cfg.getInt("profiler.max-profile-seconds", 300);
        seconds = Math.min(Math.max(1, seconds), maxSeconds);
        sender.sendMessage(this.prefixed("profile-started").replace("{type}", type).replace("{duration}", String.valueOf(seconds)));
        this.runProfile(sender, seconds, type);
        return true;
    }

    private void runProfile(CommandSender sender, int seconds, String type) {
        Map<Chunk, Integer> before = this.snapshotForProfile(type);
        Bukkit.getScheduler().runTaskLater(
                this,
                () -> {
                    Map<Chunk, Integer> after = this.snapshotForProfile(type);
                    List<Entry<Chunk, Integer>> deltas = new ArrayList<>();

                    for (Entry<Chunk, Integer> entry : after.entrySet()) {
                        int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
                        if (delta > 0) {
                            deltas.add(new SimpleEntry<>(entry.getKey(), delta));
                        }
                    }

                    deltas.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    sender.sendMessage(this.prefixed("profile-done"));
                    if (deltas.isEmpty()) {
                        sender.sendMessage(this.prefixed("profile-no-activity"));
                    } else {
                        sender.sendMessage(this.prefixed("profile-result-header").replace("{type}", type));
                        int shown = 0;

                        for (Entry<Chunk, Integer> entryx : deltas) {
                            if (shown++ >= 10) {
                                break;
                            }

                            Chunk c = entryx.getKey();
                            sender.sendMessage(
                                ChatColor.translateAlternateColorCodes(
                                    '&', "&7- &f" + c.getWorld().getName() + " (" + c.getX() + ", " + c.getZ() + ") &7activity=&e" + entryx.getValue()
                                )
                            );
                        }
                    }
                },
                (long)seconds * 20L
            );
    }

    private Map<Chunk, Integer> snapshotForProfile(String type) {
        Map<Chunk, Integer> map = new HashMap<>();

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                map.put(chunk, switch (type) {
                    case "entities" -> chunk.getEntities().length;
                    case "tile-entities" -> chunk.getTileEntities().length;
                    case "redstone" -> this.redstoneActivityThisInterval.getOrDefault(this.chunkKeyOf(chunk), 0);
                    case "chunk-loads" -> 1;
                    default -> 0;
                });
            }
        }

        return map;
    }

    private boolean handleMemory(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.memory")) {
            return true;
        }

        String key = sender.getName();
        BukkitTask existing = this.memoryViewers.remove(key);
        if (existing != null) {
            existing.cancel();
            sender.sendMessage(this.prefixed("realtime-stopped"));
            return true;
        }

        sender.sendMessage(this.prefixed("memory-header"));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    if (sender instanceof Player && !((Player)sender).isOnline()) {
                        BukkitTask t = this.memoryViewers.remove(key);
                        if (t != null) {
                            t.cancel();
                        }
                    } else {
                        double used = this.getUsedRamMb();
                        double max = this.getMaxRamMb();
                        sender.sendMessage(
                            ChatColor.translateAlternateColorCodes(
                                '&',
                                "&bHeap: &f"
                                    + (long)used
                                    + "MB &7/ &f"
                                    + (long)max
                                    + "MB &7(&e"
                                    + String.format(Locale.ROOT, "%.1f", this.getRamPercent())
                                    + "%&7)"
                            )
                        );
                    }
                },
                0L,
                40L
            );
        this.memoryViewers.put(key, task);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (this.memoryViewers.remove(key, task)) {
                task.cancel();
            }
        }, 400L);
        return true;
    }

    private boolean handlePerformance(CommandSender sender) {
        if (!this.requirePerm(sender, "lagg.performance")) {
            return true;
        }

        String key = sender.getName();
        BukkitTask existing = this.performanceViewers.remove(key);
        if (existing != null) {
            existing.cancel();
            sender.sendMessage(this.prefixed("realtime-stopped"));
            return true;
        }

        sender.sendMessage(this.prefixed("performance-header"));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    if (sender instanceof Player && !((Player)sender).isOnline()) {
                        BukkitTask t = this.performanceViewers.remove(key);
                        if (t != null) {
                            t.cancel();
                        }
                    } else {
                        sender.sendMessage(
                            ChatColor.translateAlternateColorCodes(
                                '&',
                                "&bTPS: &f"
                                    + String.format(Locale.ROOT, "%.2f", this.tpsTracker.getTps5s())
                                    + " &7(1m: &f"
                                    + String.format(Locale.ROOT, "%.2f", this.tpsTracker.getTps1m())
                                    + "&7)"
                            )
                        );
                    }
                },
                0L,
                40L
            );
        this.performanceViewers.put(key, task);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (this.performanceViewers.remove(key, task)) {
                task.cancel();
            }
        }, 400L);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        String[] lines = new String[]{
            "&b/lagg clear &7- Clears configured entities",
            "&b/lagg check [worlds...] &7- World & chunk information",
            "&b/lagg reload &7- Reloads the configuration",
            "&b/lagg killmobs &7- Kills configured mobs",
            "&b/lagg area <radius> &7- Removes entities in radius",
            "&b/lagg tpchunk <x> <z> [world] &7- Teleport to chunk",
            "&b/lagg admin <list|enable|disable> [module] &7- Manage modules",
            "&b/lagg gc &7- Force garbage collection",
            "&b/lagg tps &7- View estimated TPS",
            "&b/lagg halt &7- Toggle halted server functions",
            "&b/lagg sampleMemory <seconds> &7- Sample memory + GC",
            "&b/lagg sampleTicks <ticks> [raw] &7- Sample tick durations",
            "&b/lagg unloadchunks &7- Attempt to unload unused chunks",
            "&b/lagg profile <seconds> <type> &7- Profile chunk activity",
            "&b/lagg memory &7- Live memory heap view",
            "&b/lagg performance &7- Live main-thread view"
        };

        for (String line : lines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> subs = Arrays.asList(
            "clear",
            "check",
            "reload",
            "killmobs",
            "area",
            "tpchunk",
            "admin",
            "gc",
            "tps",
            "halt",
            "sampleMemory",
            "sampleTicks",
            "unloadchunks",
            "profile",
            "memory",
            "performance",
            "help"
        );
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();

            for (String s : subs) {
                if (s.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    out.add(s);
                }
            }

            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return Arrays.asList("list", "enable", "disable");
        }

        if (args.length != 3 || !args[0].equalsIgnoreCase("admin") || !args[1].equalsIgnoreCase("enable") && !args[1].equalsIgnoreCase("disable")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("check")) {
                List<String> names = new ArrayList<>();

                for (World w : Bukkit.getWorlds()) {
                    names.add(w.getName());
                }

                return names;
            }

            return args.length == 2 && args[0].equalsIgnoreCase("profile") ? this.cfg.getStringList("profiler.types") : Collections.emptyList();
        }

        return Arrays.asList(ModuleAdmin.moduleNames());
    }
}
