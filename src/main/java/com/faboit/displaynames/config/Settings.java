package com.faboit.displaynames.config;

import com.faboit.displaynames.text.LegacyColors;
import com.faboit.displaynames.text.NametagTemplate;
import com.faboit.displaynames.text.TextRenderer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable snapshot of config.yml.
 *
 * <p>A reload builds a brand new instance and publishes it through a single volatile field, so
 * refresh tasks running on other region threads never observe a half-applied configuration and
 * never need a lock to read one.
 */
public final class Settings {

    private final int refreshInterval;
    private final boolean staggerUpdates;
    private final DisplayOptions display;
    private final Profile defaultProfile;
    private final List<Profile> profiles;
    private final Set<String> disabledWorlds;

    private final boolean hideFromSelf;
    private final boolean hideVanillaNametag;
    private final boolean hideWhileSneaking;
    private final boolean hideWhileInvisible;
    private final boolean hideInSpectator;
    private final boolean hideWhileVanished;

    private final boolean skipWithoutViewers;
    private final int viewerGridSize;
    private final LegacyColors.Mode legacyMode;
    private final int componentCacheSize;

    private Settings(FileConfiguration config, TextRenderer renderer, Logger logger) {
        this.refreshInterval = Math.max(0, config.getInt("refresh-interval", 10));
        this.display = DisplayOptions.load(section(config, "display"), section(config, "offset"), logger);

        List<String> defaultLines = config.getStringList("nametag.lines");
        if (defaultLines.isEmpty()) {
            logger.warning("nametag.lines is empty - players without a matching profile get no nametag.");
        }
        this.defaultProfile = new Profile("default", null, Integer.MIN_VALUE,
                NametagTemplate.compile(defaultLines, renderer));
        this.profiles = loadProfiles(config.getConfigurationSection("profiles"), renderer, logger);

        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("visibility.disabled-worlds")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        this.disabledWorlds = worlds;

        ConfigurationSection visibility = section(config, "visibility");
        this.hideFromSelf = visibility.getBoolean("hide-from-self", true);
        this.hideVanillaNametag = visibility.getBoolean("hide-vanilla-nametag", true);
        this.hideWhileSneaking = visibility.getBoolean("hide-while-sneaking", false);
        this.hideWhileInvisible = visibility.getBoolean("hide-while-invisible", true);
        this.hideInSpectator = visibility.getBoolean("hide-in-spectator", true);
        this.hideWhileVanished = visibility.getBoolean("hide-while-vanished", true);

        ConfigurationSection performance = section(config, "performance");
        this.skipWithoutViewers = performance.getBoolean("skip-updates-without-viewers", true);
        this.staggerUpdates = performance.getBoolean("stagger-updates", true);
        this.componentCacheSize = Math.max(0, performance.getInt("component-cache-size", 512));

        int configuredGrid = performance.getInt("viewer-grid-size", 128);
        int minimumGrid = (int) Math.ceil(display.renderDistanceBlocks());
        if (configuredGrid < minimumGrid) {
            logger.warning("performance.viewer-grid-size (" + configuredGrid + ") is smaller than the tag render "
                    + "distance (" + minimumGrid + " blocks); raising it so nametags are not skipped while visible.");
            configuredGrid = minimumGrid;
        }
        this.viewerGridSize = Math.max(16, configuredGrid);

        this.legacyMode = LegacyColors.Mode.parse(config.getString("text.legacy-colors"), LegacyColors.Mode.SECTION);
    }

    /**
     * Reads the legacy-colour and cache settings needed to build the renderer, before the
     * renderer itself is required to compile templates.
     */
    public static TextRenderer createRenderer(FileConfiguration config, Logger logger) {
        return new TextRenderer(logger,
                LegacyColors.Mode.parse(config.getString("text.legacy-colors"), LegacyColors.Mode.SECTION),
                Math.max(0, config.getInt("performance.component-cache-size", 512)));
    }

    public static Settings load(FileConfiguration config, TextRenderer renderer, Logger logger) {
        return new Settings(config, renderer, logger);
    }

    private static List<Profile> loadProfiles(ConfigurationSection root, TextRenderer renderer, Logger logger) {
        if (root == null) return List.of();

        List<Profile> loaded = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) continue;

            String permission = entry.getString("permission");
            if (permission == null || permission.isBlank()) {
                logger.warning("Profile '" + id + "' has no permission set and was skipped.");
                continue;
            }
            List<String> lines = entry.getStringList("lines");
            if (lines.isEmpty()) {
                logger.warning("Profile '" + id + "' has no lines and was skipped.");
                continue;
            }
            loaded.add(new Profile(id, permission, entry.getInt("priority", 0),
                    NametagTemplate.compile(lines, renderer)));
        }
        loaded.sort(Comparator.comparingInt(Profile::priority).reversed());
        return Collections.unmodifiableList(loaded);
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection existing = config.getConfigurationSection(path);
        return existing != null ? existing : config.createSection(path);
    }

    /** Highest-priority profile the player has permission for, or the default format. */
    public Profile profileFor(Player player) {
        // profiles is sorted by descending priority, so the first hit is the winner.
        for (int i = 0, size = profiles.size(); i < size; i++) {
            Profile profile = profiles.get(i);
            if (player.hasPermission(profile.permission())) return profile;
        }
        return defaultProfile;
    }

    public boolean worldDisabled(String worldName) {
        return !disabledWorlds.isEmpty() && disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public int refreshInterval() {
        return refreshInterval;
    }

    /** {@code true} when placeholders should be re-evaluated on a timer. */
    public boolean autoRefresh() {
        return refreshInterval > 0;
    }

    /** How often the upkeep task runs; static setups still need a slow re-mount guard. */
    public long taskPeriod() {
        return refreshInterval > 0 ? refreshInterval : 40L;
    }

    public boolean staggerUpdates() {
        return staggerUpdates;
    }

    public DisplayOptions display() {
        return display;
    }

    public List<Profile> profiles() {
        return profiles;
    }

    public boolean hideFromSelf() {
        return hideFromSelf;
    }

    public boolean hideVanillaNametag() {
        return hideVanillaNametag;
    }

    public boolean hideWhileSneaking() {
        return hideWhileSneaking;
    }

    public boolean hideWhileInvisible() {
        return hideWhileInvisible;
    }

    public boolean hideInSpectator() {
        return hideInSpectator;
    }

    public boolean hideWhileVanished() {
        return hideWhileVanished;
    }

    public boolean skipWithoutViewers() {
        return skipWithoutViewers;
    }

    public int viewerGridSize() {
        return viewerGridSize;
    }

    public LegacyColors.Mode legacyMode() {
        return legacyMode;
    }

    public int componentCacheSize() {
        return componentCacheSize;
    }
}
