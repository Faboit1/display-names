package com.faboit.displaynames.config;

import com.faboit.displaynames.condition.Condition;
import com.faboit.displaynames.condition.Conditions;
import com.faboit.displaynames.nametag.TeamGuard;
import com.faboit.displaynames.text.LegacyColors;
import com.faboit.displaynames.text.NametagTemplate;
import com.faboit.displaynames.text.PlaceholderResolver;
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
    private final Anchor anchor;
    private final long followInterval;
    private final Profile defaultProfile;
    private final List<Profile> profiles;
    private final Conditions conditions;
    private final Condition hideCondition;
    private final Set<String> disabledWorlds;

    private final boolean hideFromSelf;
    private final TeamGuard.Mode teamMode;
    private final String teamName;
    private final long teamReassertInterval;
    private final boolean hideWhileSneaking;
    private final boolean hideWhileInvisible;
    private final boolean seeThroughWhileSneaking;
    private final boolean seeThroughWhileInvisible;
    private final boolean hideInSpectator;
    private final boolean hideWhileVanished;

    private final boolean skipWithoutViewers;
    private final int viewerGridSize;
    private final LegacyColors.Mode legacyMode;
    private final int componentCacheSize;

    private Settings(FileConfiguration config, TextRenderer renderer, Logger logger) {
        this.refreshInterval = Math.max(0, config.getInt("refresh-interval", 10));
        this.display = DisplayOptions.load(config.getConfigurationSection("display"),
                config.getConfigurationSection("offset"), DisplayOptions.defaults(), logger);

        ConfigurationSection displaySection = section(config, "display");
        this.anchor = Anchor.parse(displaySection.getString("anchor"), Anchor.MOUNT);
        this.followInterval = Math.max(1L, displaySection.getLong("follow-interval", 1L));

        // Loaded before the profiles, which may name a condition, and before visibility, which may
        // hide a tag by one.
        this.conditions = Conditions.load(config.getConfigurationSection("conditions"), logger);

        List<String> defaultLines = config.getStringList("nametag.lines");
        if (defaultLines.isEmpty()) {
            logger.warning("nametag.lines is empty - players without a matching profile get no nametag.");
        }
        this.defaultProfile = new Profile("default", null, Integer.MIN_VALUE, null,
                NametagTemplate.compile(defaultLines, renderer), display);
        this.profiles = loadProfiles(config.getConfigurationSection("profiles"), renderer, display,
                conditions, defaultProfile.template(), logger);

        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("visibility.disabled-worlds")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        this.disabledWorlds = worlds;

        ConfigurationSection visibility = section(config, "visibility");
        this.hideFromSelf = visibility.getBoolean("hide-from-self", true);
        this.hideWhileSneaking = visibility.getBoolean("hide-while-sneaking", false);
        // On by default, unlike sneaking: vanilla hides the username plate of an invisible
        // player outright, so leaving the tag up is the one case where a custom nametag gives
        // away something the game itself hides.
        this.hideWhileInvisible = visibility.getBoolean("hide-while-invisible", true);
        // Sneaking drops the tag back to line-of-sight only rather than hiding it, which is what
        // vanilla nametags do and what the see-through default trades away.
        this.seeThroughWhileSneaking = visibility.getBoolean("see-through-while-sneaking", false);
        this.seeThroughWhileInvisible = visibility.getBoolean("see-through-while-invisible", false);
        this.hideInSpectator = visibility.getBoolean("hide-in-spectator", true);
        this.hideWhileVanished = visibility.getBoolean("hide-while-vanished", true);
        this.hideCondition = conditions.reference(visibility.getString("hide-condition"),
                "visibility.hide-condition", logger);

        // Accepts both the old `hide-vanilla-nametag: true` boolean and the current block form.
        if (visibility.isConfigurationSection("hide-vanilla-nametag")) {
            ConfigurationSection vanilla = visibility.getConfigurationSection("hide-vanilla-nametag");
            this.teamMode = TeamGuard.Mode.parse(vanilla.getString("mode"), TeamGuard.Mode.ADOPT);
            this.teamName = vanilla.getString("team-name", "displaynames");
            this.teamReassertInterval = Math.max(0L, vanilla.getLong("reassert-interval", 20L));
        } else {
            this.teamMode = visibility.getBoolean("hide-vanilla-nametag", true)
                    ? TeamGuard.Mode.ADOPT : TeamGuard.Mode.NONE;
            this.teamName = "displaynames";
            this.teamReassertInterval = 20L;
        }

        ConfigurationSection performance = section(config, "performance");
        this.skipWithoutViewers = performance.getBoolean("skip-updates-without-viewers", true);
        this.staggerUpdates = performance.getBoolean("stagger-updates", true);
        this.componentCacheSize = Math.max(0, performance.getInt("component-cache-size", 512));

        int configuredGrid = performance.getInt("viewer-grid-size", 128);
        int minimumGrid = (int) Math.ceil(widestRenderDistance());
        if (configuredGrid < minimumGrid) {
            logger.warning("performance.viewer-grid-size (" + configuredGrid + ") is smaller than the tag render "
                    + "distance (" + minimumGrid + " blocks); raising it so nametags are not skipped while visible.");
            configuredGrid = minimumGrid;
        }
        this.viewerGridSize = Math.max(16, configuredGrid);

        this.legacyMode = LegacyColors.Mode.parse(config.getString("text.legacy-colors"), LegacyColors.Mode.SECTION);
    }

    /**
     * Reads the renderer's own settings before the renderer is needed to compile templates.
     */
    public static TextRenderer createRenderer(FileConfiguration config, Logger logger) {
        return new TextRenderer(logger,
                LegacyColors.Mode.parse(config.getString("text.legacy-colors"), LegacyColors.Mode.SECTION),
                Math.max(0, config.getInt("performance.component-cache-size", 512)));
    }

    public static Settings load(FileConfiguration config, TextRenderer renderer, Logger logger) {
        return new Settings(config, renderer, logger);
    }

    /**
     * Reads the {@code profiles:} section.
     *
     * <p>A profile has to actually do something to be worth a per-refresh check, and it has to be
     * reachable at all, so two shapes are dropped with a warning: one gated by neither a
     * permission nor a condition (it would apply to everybody, silently outranking
     * {@code nametag.lines}), and one that overrides no text and no appearance.
     *
     * <p>Lines are optional. A profile with none inherits {@code nametag.lines} and exists purely
     * to override settings - a bigger scale while a player is in combat, a higher tag for someone
     * riding a horse - which is the whole point of gating a profile on a condition.
     */
    private static List<Profile> loadProfiles(ConfigurationSection root, TextRenderer renderer,
                                              DisplayOptions parent, Conditions conditions,
                                              NametagTemplate inherited, Logger logger) {
        if (root == null) return List.of();

        List<Profile> loaded = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) continue;

            String permission = entry.getString("permission");
            if (permission != null && permission.isBlank()) permission = null;
            Condition condition = conditions.reference(entry.getString("condition"),
                    "profiles." + id + ".condition", logger);
            if (permission == null && condition == null) {
                logger.warning("Profile '" + id + "' has neither a permission nor a condition, so it "
                        + "would apply to everybody and override nametag.lines. It was skipped.");
                continue;
            }

            // Inherits the global appearance unless the profile overrides part of it. An untouched
            // profile gets the parent instance back, which is how "overrides nothing" is spotted.
            DisplayOptions profileDisplay = DisplayOptions.load(entry.getConfigurationSection("display"),
                    entry.getConfigurationSection("offset"), parent, logger);

            List<String> lines = entry.getStringList("lines");
            if (lines.isEmpty() && profileDisplay == parent) {
                logger.warning("Profile '" + id + "' has no lines and overrides no display setting, "
                        + "so it would change nothing. It was skipped.");
                continue;
            }
            NametagTemplate template = lines.isEmpty()
                    ? inherited : NametagTemplate.compile(lines, renderer);

            loaded.add(new Profile(id, permission, entry.getInt("priority", 0), condition,
                    template, profileDisplay));
        }
        loaded.sort(Comparator.comparingInt(Profile::priority).reversed());
        return Collections.unmodifiableList(loaded);
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection existing = config.getConfigurationSection(path);
        return existing != null ? existing : config.createSection(path);
    }

    /** The largest render distance across every profile - the viewer grid must cover all of them. */
    private double widestRenderDistance() {
        double widest = display.renderDistanceBlocks();
        for (Profile profile : profiles) {
            widest = Math.max(widest, profile.display().renderDistanceBlocks());
        }
        return widest;
    }

    /**
     * Highest-priority profile this player matches right now, or the default.
     *
     * <p>Permission first, condition second, and only when the permission passed: a permission is
     * a map lookup, while a condition can cost a PlaceholderAPI call per check, and this runs on
     * every refresh for every player.
     *
     * @param resolver used by conditions; pass the live one, since a condition on an unresolved
     *                 placeholder is a condition that never matches
     */
    public Profile profileFor(Player player, PlaceholderResolver resolver) {
        // profiles is sorted by descending priority, so the first hit is the winner.
        for (int i = 0, size = profiles.size(); i < size; i++) {
            Profile profile = profiles.get(i);
            String permission = profile.permission();
            if (permission != null && !player.hasPermission(permission)) continue;
            Condition condition = profile.condition();
            if (condition != null && !condition.matches(player, conditions, resolver, 0)) continue;
            return profile;
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

    /** How the tag is kept above its player. */
    public Anchor anchor() {
        return anchor;
    }

    /** Ticks between position updates; only used by {@link Anchor#FOLLOW}. */
    public long followInterval() {
        return followInterval;
    }

    public List<Profile> profiles() {
        return profiles;
    }

    /** Every condition declared in config.yml; never null, empty when none are. */
    public Conditions conditions() {
        return conditions;
    }

    /** Condition that hides a tag while it matches, or {@code null} when none is configured. */
    public Condition hideCondition() {
        return hideCondition;
    }

    /** The format used by players who match no profile. */
    Profile defaultProfile() {
        return defaultProfile;
    }

    public boolean hideFromSelf() {
        return hideFromSelf;
    }

    public TeamGuard.Mode teamMode() {
        return teamMode;
    }

    public String teamName() {
        return teamName;
    }

    public long teamReassertInterval() {
        return teamReassertInterval;
    }

    public boolean hideWhileSneaking() {
        return hideWhileSneaking;
    }

    public boolean hideWhileInvisible() {
        return hideWhileInvisible;
    }

    /** Whether a sneaking player's tag stays visible through terrain. */
    public boolean seeThroughWhileSneaking() {
        return seeThroughWhileSneaking;
    }

    /** Whether an invisible player's tag stays visible through terrain. */
    public boolean seeThroughWhileInvisible() {
        return seeThroughWhileInvisible;
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
