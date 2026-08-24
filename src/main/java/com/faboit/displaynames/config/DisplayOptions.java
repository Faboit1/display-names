package com.faboit.displaynames.config;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Immutable snapshot of the visual settings applied to every nametag entity.
 *
 * <p>All of this is resolved once at load time, so spawning a nametag is just a handful of
 * setter calls on an entity that has not been added to the world yet.
 */
public final class DisplayOptions {

    /**
     * Vertical offset of a passenger's anchor point on a player, in blocks.
     *
     * <p>Vanilla attaches passengers at {@code height * 0.75} and a player is 1.8 blocks tall.
     * Subtracting it lets {@code offset.y} in the config mean "blocks above the player's feet",
     * which is what people actually expect to configure.
     */
    public static final float DEFAULT_MOUNT_ANCHOR = 1.35f;

    private final Display.Billboard billboard;
    private final Color background;
    private final byte textOpacity;
    private final boolean shadow;
    private final boolean seeThrough;
    private final TextDisplay.TextAlignment alignment;
    private final int lineWidth;
    private final float viewRange;
    private final Display.Brightness brightness;
    private final Transformation transformation;

    private DisplayOptions(Display.Billboard billboard, Color background, byte textOpacity, boolean shadow,
                           boolean seeThrough, TextDisplay.TextAlignment alignment, int lineWidth, float viewRange,
                           Display.Brightness brightness, Transformation transformation) {
        this.billboard = billboard;
        this.background = background;
        this.textOpacity = textOpacity;
        this.shadow = shadow;
        this.seeThrough = seeThrough;
        this.alignment = alignment;
        this.lineWidth = lineWidth;
        this.viewRange = viewRange;
        this.brightness = brightness;
        this.transformation = transformation;
    }

    public static DisplayOptions load(ConfigurationSection display, ConfigurationSection offset, Logger logger) {
        Display.Billboard billboard = enumValue(Display.Billboard.class,
                display.getString("billboard"), Display.Billboard.CENTER, logger, "display.billboard");
        TextDisplay.TextAlignment alignment = enumValue(TextDisplay.TextAlignment.class,
                display.getString("alignment"), TextDisplay.TextAlignment.CENTER, logger, "display.alignment");

        Color background = parseBackground(display.getString("background", "transparent"), logger);
        byte opacity = parseOpacity(display.getInt("text-opacity", 255));
        boolean shadow = display.getBoolean("shadow", false);
        boolean seeThrough = display.getBoolean("see-through", false);
        int lineWidth = Math.max(1, display.getInt("line-width", 200));
        float viewRange = (float) Math.max(0.0D, display.getDouble("view-range", 1.0D));
        Display.Brightness brightness = display.getBoolean("full-brightness", true)
                ? new Display.Brightness(15, 15)
                : null;

        float scale = (float) display.getDouble("scale", 1.0D);
        if (scale <= 0.0F) {
            logger.warning("display.scale must be greater than 0, using 1.0");
            scale = 1.0F;
        }

        float anchor = (float) offset.getDouble("mount-anchor", DEFAULT_MOUNT_ANCHOR);
        Vector3f translation = new Vector3f(
                (float) offset.getDouble("x", 0.0D),
                (float) offset.getDouble("y", 2.5D) - anchor,
                (float) offset.getDouble("z", 0.0D));
        Transformation transformation = new Transformation(
                translation, new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf());

        return new DisplayOptions(billboard, background, opacity, shadow, seeThrough, alignment,
                lineWidth, viewRange, brightness, transformation);
    }

    /** Configures a freshly created entity. Called from the spawn consumer, before it is added. */
    public void apply(TextDisplay entity) {
        entity.setBillboard(billboard);
        entity.setBackgroundColor(background);
        entity.setTextOpacity(textOpacity);
        entity.setShadowed(shadow);
        entity.setSeeThrough(seeThrough);
        entity.setAlignment(alignment);
        entity.setLineWidth(lineWidth);
        entity.setViewRange(viewRange);
        entity.setBrightness(brightness);
        entity.setTransformation(transformation);

        // The tag rides the player, so the client interpolates it for free - the server never
        // has to move it, and any interpolation of our own would only add lag to the motion.
        entity.setInterpolationDelay(0);
        entity.setInterpolationDuration(0);
        entity.setTeleportDuration(0);

        // A zero-size culling box means "never cull", which keeps tall multi-line tags from
        // vanishing when the anchor point leaves the frustum.
        entity.setDisplayWidth(0.0F);
        entity.setDisplayHeight(0.0F);

        // Never write these to region files; they are rebuilt on join.
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setVisibleByDefault(true);
    }

    /** Render distance of the tag in blocks - used to size the viewer grid. */
    public double renderDistanceBlocks() {
        return viewRange * 64.0D;
    }

    private static Color parseBackground(String raw, Logger logger) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("default")) return null;
        if (value.equalsIgnoreCase("transparent") || value.equalsIgnoreCase("none")) {
            return Color.fromARGB(0, 0, 0, 0);
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            if (hex.length() == 6) return Color.fromARGB(0xFF, digits(hex, 0), digits(hex, 2), digits(hex, 4));
            if (hex.length() == 8) {
                return Color.fromARGB(digits(hex, 0), digits(hex, 2), digits(hex, 4), digits(hex, 6));
            }
        } catch (NumberFormatException ignored) {
            // fall through to the warning below
        }
        logger.warning("Could not read display.background '" + raw + "', expected 'default', 'transparent', "
                + "#RRGGBB or #AARRGGBB. Falling back to transparent.");
        return Color.fromARGB(0, 0, 0, 0);
    }

    private static int digits(String hex, int index) {
        return Integer.parseInt(hex.substring(index, index + 2), 16);
    }

    private static byte parseOpacity(int configured) {
        if (configured >= 255) return (byte) -1; // -1 is the vanilla "fully opaque" sentinel
        return (byte) Math.max(1, Math.min(254, configured));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback, Logger logger, String path) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            logger.warning("Unknown value '" + raw + "' for " + path + ", using " + fallback.name());
            return fallback;
        }
    }
}
