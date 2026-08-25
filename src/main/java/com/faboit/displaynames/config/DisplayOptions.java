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
 * Every visual property of a nametag entity, resolved once at config load.
 *
 * <p>Instances inherit: a profile's {@code display} block only has to name what it changes, and
 * anything it leaves out comes from the global block. Because an instance is built once per
 * profile per load, identity comparison is enough to notice that a player's appearance changed
 * and their entity needs rebuilding.
 */
public final class DisplayOptions {

    /**
     * Vertical offset of a passenger's anchor point on a player, in blocks.
     *
     * <p>Vanilla attaches passengers at {@code height * 0.75} and a player is 1.8 blocks tall.
     * Subtracting it lets {@code offset.y} mean "blocks above the player's feet", which is what
     * people actually want to configure.
     */
    public static final float DEFAULT_MOUNT_ANCHOR = 1.35F;

    private final Display.Billboard billboard;
    private final Vector3f translation;
    private final Vector3f scale;
    private final Quaternionf leftRotation;
    private final Quaternionf rightRotation;

    private final Color background;
    private final byte textOpacity;
    private final boolean textShadow;
    private final boolean seeThrough;
    private final TextDisplay.TextAlignment alignment;
    private final int lineWidth;

    private final float viewRange;
    private final Display.Brightness brightness;
    private final float shadowRadius;
    private final float shadowStrength;
    private final float cullingWidth;
    private final float cullingHeight;
    private final Color glowColor;

    private final int interpolationDelay;
    private final int interpolationDuration;
    private final int teleportDuration;

    private DisplayOptions(Builder builder) {
        this.billboard = builder.billboard;
        this.translation = builder.translation;
        this.scale = builder.scale;
        this.leftRotation = builder.leftRotation;
        this.rightRotation = builder.rightRotation;
        this.background = builder.background;
        this.textOpacity = builder.textOpacity;
        this.textShadow = builder.textShadow;
        this.seeThrough = builder.seeThrough;
        this.alignment = builder.alignment;
        this.lineWidth = builder.lineWidth;
        this.viewRange = builder.viewRange;
        this.brightness = builder.brightness;
        this.shadowRadius = builder.shadowRadius;
        this.shadowStrength = builder.shadowStrength;
        this.cullingWidth = builder.cullingWidth;
        this.cullingHeight = builder.cullingHeight;
        this.glowColor = builder.glowColor;
        this.interpolationDelay = builder.interpolationDelay;
        this.interpolationDuration = builder.interpolationDuration;
        this.teleportDuration = builder.teleportDuration;
    }

    /** The built-in defaults, used as the parent of the global {@code display} block. */
    public static DisplayOptions defaults() {
        return new Builder().build();
    }

    /**
     * @param display the {@code display} block, or {@code null} to inherit all of it
     * @param offset  the {@code offset} block, or {@code null} to inherit the position
     * @param parent  values to fall back on for anything not set
     */
    public static DisplayOptions load(ConfigurationSection display, ConfigurationSection offset,
                                      DisplayOptions parent, Logger logger) {
        if (display == null && offset == null) return parent;

        Builder builder = parent.toBuilder();
        if (offset != null) builder.translation = readTranslation(offset, parent.translation);
        if (display == null) return builder.build();

        builder.billboard = readEnum(Display.Billboard.class, display, "billboard", parent.billboard, logger);
        builder.alignment = readEnum(TextDisplay.TextAlignment.class, display, "alignment",
                parent.alignment, logger);

        // A nested offset inside a profile's display block overrides the sibling one.
        if (display.isConfigurationSection("offset")) {
            builder.translation = readTranslation(display.getConfigurationSection("offset"), builder.translation);
        }

        builder.scale = readScale(display, parent.scale, logger);
        builder.leftRotation = readRotation(display, parent.leftRotation);

        builder.background = readBackground(display, parent.background, logger);
        builder.textOpacity = display.contains("text-opacity")
                ? encodeOpacity(display.getInt("text-opacity"))
                : parent.textOpacity;
        builder.textShadow = display.getBoolean("text-shadow", parent.textShadow);
        builder.seeThrough = display.getBoolean("see-through", parent.seeThrough);
        builder.lineWidth = Math.max(1, display.getInt("line-width", parent.lineWidth));
        builder.viewRange = (float) Math.max(0.0D, display.getDouble("view-range", parent.viewRange));
        builder.brightness = readBrightness(display, parent.brightness, logger);

        ConfigurationSection entityShadow = display.getConfigurationSection("entity-shadow");
        if (entityShadow != null) {
            builder.shadowRadius = (float) Math.max(0.0D,
                    entityShadow.getDouble("radius", parent.shadowRadius));
            builder.shadowStrength = (float) Math.max(0.0D,
                    entityShadow.getDouble("strength", parent.shadowStrength));
        }

        ConfigurationSection culling = display.getConfigurationSection("culling");
        if (culling != null) {
            builder.cullingWidth = (float) Math.max(0.0D, culling.getDouble("width", parent.cullingWidth));
            builder.cullingHeight = (float) Math.max(0.0D, culling.getDouble("height", parent.cullingHeight));
        }

        builder.glowColor = display.contains("glow-color")
                ? readRgb(display.getString("glow-color"), null, "display.glow-color", logger)
                : parent.glowColor;

        ConfigurationSection interpolation = display.getConfigurationSection("interpolation");
        if (interpolation != null) {
            builder.interpolationDelay = Math.max(0,
                    interpolation.getInt("delay", parent.interpolationDelay));
            builder.interpolationDuration = Math.max(0,
                    interpolation.getInt("duration", parent.interpolationDuration));
            builder.teleportDuration = clamp(
                    interpolation.getInt("teleport-duration", parent.teleportDuration), 0, 59);
        }

        return builder.build();
    }

    /** Configures a freshly created entity, from inside the spawn consumer. */
    public void apply(TextDisplay entity) {
        entity.setBillboard(billboard);
        entity.setBackgroundColor(background);
        entity.setTextOpacity(textOpacity);
        entity.setShadowed(textShadow);
        entity.setSeeThrough(seeThrough);
        entity.setAlignment(alignment);
        entity.setLineWidth(lineWidth);
        entity.setViewRange(viewRange);
        entity.setBrightness(brightness);
        entity.setGlowColorOverride(glowColor);
        entity.setGlowing(glowColor != null);
        entity.setShadowRadius(shadowRadius);
        entity.setShadowStrength(shadowStrength);
        entity.setDisplayWidth(cullingWidth);
        entity.setDisplayHeight(cullingHeight);
        entity.setInterpolationDelay(interpolationDelay);
        entity.setInterpolationDuration(interpolationDuration);
        entity.setTeleportDuration(teleportDuration);
        entity.setTransformation(new Transformation(translation, leftRotation, scale, rightRotation));

        // Never written to region files; rebuilt on join.
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setVisibleByDefault(true);
    }

    /** Render distance of the tag in blocks, used to size the viewer grid. */
    public double renderDistanceBlocks() {
        return viewRange * 64.0D;
    }

    public Display.Billboard billboard() {
        return billboard;
    }

    // Package-private views of the parsed values, for tests.

    Vector3f translation() {
        return translation;
    }

    Vector3f scale() {
        return scale;
    }

    Quaternionf leftRotation() {
        return leftRotation;
    }

    Color background() {
        return background;
    }

    byte textOpacity() {
        return textOpacity;
    }

    boolean textShadow() {
        return textShadow;
    }

    boolean seeThrough() {
        return seeThrough;
    }

    TextDisplay.TextAlignment alignment() {
        return alignment;
    }

    int lineWidth() {
        return lineWidth;
    }

    float viewRange() {
        return viewRange;
    }

    Display.Brightness brightness() {
        return brightness;
    }

    Color glowColor() {
        return glowColor;
    }

    float shadowRadius() {
        return shadowRadius;
    }

    int teleportDuration() {
        return teleportDuration;
    }

    // ---------------------------------------------------------------- parsing

    private static Vector3f readTranslation(ConfigurationSection offset, Vector3f parent) {
        float anchor = (float) offset.getDouble("mount-anchor", DEFAULT_MOUNT_ANCHOR);
        return new Vector3f(
                (float) offset.getDouble("x", parent.x()),
                (float) offset.getDouble("y", parent.y() + DEFAULT_MOUNT_ANCHOR) - anchor,
                (float) offset.getDouble("z", parent.z()));
    }

    private static Vector3f readScale(ConfigurationSection display, Vector3f parent, Logger logger) {
        if (!display.contains("scale")) return parent;

        // Accept both `scale: 1.5` and a per-axis block.
        if (display.isConfigurationSection("scale")) {
            ConfigurationSection scale = display.getConfigurationSection("scale");
            return new Vector3f(
                    positive(scale.getDouble("x", parent.x()), parent.x(), "display.scale.x", logger),
                    positive(scale.getDouble("y", parent.y()), parent.y(), "display.scale.y", logger),
                    positive(scale.getDouble("z", parent.z()), parent.z(), "display.scale.z", logger));
        }
        float uniform = positive(display.getDouble("scale"), 1.0F, "display.scale", logger);
        return new Vector3f(uniform, uniform, uniform);
    }

    /**
     * Builds the orientation used when the billboard does not face the viewer on every axis.
     *
     * <p>Yaw follows Minecraft's convention - 0 faces south (+Z) and increasing yaw turns
     * clockwise seen from above - which is why it is negated for JOML's right-handed rotation.
     */
    private static Quaternionf readRotation(ConfigurationSection display, Quaternionf parent) {
        ConfigurationSection rotation = display.getConfigurationSection("rotation");
        if (rotation == null) return parent;

        double yaw = rotation.getDouble("yaw", 0.0D);
        double pitch = rotation.getDouble("pitch", 0.0D);
        double roll = rotation.getDouble("roll", 0.0D);

        // Spelling out three zeroes, as the shipped config does, is not a rotation. Inheriting
        // avoids both a pointless object and a negated zero in the quaternion's components.
        if (yaw == 0.0D && pitch == 0.0D && roll == 0.0D) return parent;

        return new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-yaw),
                (float) Math.toRadians(pitch),
                (float) Math.toRadians(roll));
    }

    private static Display.Brightness readBrightness(ConfigurationSection display,
                                                     Display.Brightness parent, Logger logger) {
        if (!display.contains("brightness")) return parent;

        // `brightness: false` means "use world lighting".
        if (display.isBoolean("brightness")) {
            return display.getBoolean("brightness") ? new Display.Brightness(15, 15) : null;
        }
        ConfigurationSection brightness = display.getConfigurationSection("brightness");
        if (brightness == null) return parent;
        if (!brightness.getBoolean("enabled", true)) return null;
        return new Display.Brightness(
                clamp(brightness.getInt("block", 15), 0, 15),
                clamp(brightness.getInt("sky", 15), 0, 15));
    }

    private static Color readBackground(ConfigurationSection display, Color parent, Logger logger) {
        if (!display.contains("background")) return parent;

        // Legacy string form: "default", "transparent", "#RRGGBB", "#AARRGGBB".
        if (display.isString("background")) {
            return readBackgroundString(display.getString("background"), parent, logger);
        }
        ConfigurationSection background = display.getConfigurationSection("background");
        if (background == null) return parent;

        String mode = background.getString("mode", "CUSTOM").trim().toUpperCase(Locale.ROOT);
        switch (mode) {
            case "CLIENT", "DEFAULT" -> {
                // null tells the client to use its own Text Background Opacity setting.
                return null;
            }
            case "NONE", "TRANSPARENT" -> {
                return Color.fromARGB(0, 0, 0, 0);
            }
            case "CUSTOM" -> {
                Color rgb = readRgb(background.getString("color", "#000000"), Color.BLACK,
                        "display.background.color", logger);
                return Color.fromARGB(clamp(background.getInt("opacity", 64), 0, 255),
                        rgb.getRed(), rgb.getGreen(), rgb.getBlue());
            }
            default -> {
                logger.warning("Unknown display.background.mode '" + mode + "', expected CLIENT, NONE or CUSTOM.");
                return parent;
            }
        }
    }

    private static Color readBackgroundString(String raw, Color parent, Logger logger) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("default")) return null;
        if (value.equalsIgnoreCase("transparent") || value.equalsIgnoreCase("none")) {
            return Color.fromARGB(0, 0, 0, 0);
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            if (hex.length() == 6) return Color.fromARGB(0xFF, pair(hex, 0), pair(hex, 2), pair(hex, 4));
            if (hex.length() == 8) return Color.fromARGB(pair(hex, 0), pair(hex, 2), pair(hex, 4), pair(hex, 6));
        } catch (NumberFormatException ignored) {
            // handled below
        }
        logger.warning("Could not read display.background '" + raw + "'. Keeping the previous value.");
        return parent;
    }

    private static Color readRgb(String raw, Color fallback, String path, Logger logger) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("none")) return fallback;
        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            if (hex.length() == 6) return Color.fromRGB(pair(hex, 0), pair(hex, 2), pair(hex, 4));
        } catch (NumberFormatException ignored) {
            // handled below
        }
        logger.warning("Could not read " + path + " '" + raw + "', expected #RRGGBB or 'none'.");
        return fallback;
    }

    private static int pair(String hex, int index) {
        return Integer.parseInt(hex.substring(index, index + 2), 16);
    }

    /** Vanilla treats -1 as "fully opaque"; 0 is rejected and anything under ~26 is invisible. */
    private static byte encodeOpacity(int configured) {
        if (configured >= 255) return (byte) -1;
        return (byte) clamp(configured, 1, 254);
    }

    private static float positive(double value, float fallback, String path, Logger logger) {
        if (value > 0.0D) return (float) value;
        logger.warning(path + " must be greater than 0, using " + fallback);
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static <E extends Enum<E>> E readEnum(Class<E> type, ConfigurationSection section,
                                                  String path, E parent, Logger logger) {
        String raw = section.getString(path);
        if (raw == null || raw.isBlank()) return parent;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            logger.warning("Unknown value '" + raw + "' for display." + path + ", using " + parent.name()
                    + ". Valid values: " + String.join(", ", names(type)));
            return parent;
        }
    }

    private static <E extends Enum<E>> String[] names(Class<E> type) {
        E[] constants = type.getEnumConstants();
        String[] names = new String[constants.length];
        for (int i = 0; i < constants.length; i++) {
            names[i] = constants[i].name();
        }
        return names;
    }

    private Builder toBuilder() {
        Builder builder = new Builder();
        builder.billboard = billboard;
        builder.translation = translation;
        builder.scale = scale;
        builder.leftRotation = leftRotation;
        builder.rightRotation = rightRotation;
        builder.background = background;
        builder.textOpacity = textOpacity;
        builder.textShadow = textShadow;
        builder.seeThrough = seeThrough;
        builder.alignment = alignment;
        builder.lineWidth = lineWidth;
        builder.viewRange = viewRange;
        builder.brightness = brightness;
        builder.shadowRadius = shadowRadius;
        builder.shadowStrength = shadowStrength;
        builder.cullingWidth = cullingWidth;
        builder.cullingHeight = cullingHeight;
        builder.glowColor = glowColor;
        builder.interpolationDelay = interpolationDelay;
        builder.interpolationDuration = interpolationDuration;
        builder.teleportDuration = teleportDuration;
        return builder;
    }

    private static final class Builder {
        private Display.Billboard billboard = Display.Billboard.CENTER;
        private Vector3f translation = new Vector3f(0.0F, 2.5F - DEFAULT_MOUNT_ANCHOR, 0.0F);
        private Vector3f scale = new Vector3f(1.0F, 1.0F, 1.0F);
        private Quaternionf leftRotation = new Quaternionf();
        private Quaternionf rightRotation = new Quaternionf();
        private Color background = Color.fromARGB(0, 0, 0, 0);
        private byte textOpacity = (byte) -1;
        private boolean textShadow;
        private boolean seeThrough;
        private TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.CENTER;
        private int lineWidth = 200;
        private float viewRange = 1.0F;
        private Display.Brightness brightness = new Display.Brightness(15, 15);
        private float shadowRadius;
        private float shadowStrength = 1.0F;
        private float cullingWidth;
        private float cullingHeight;
        private Color glowColor;
        private int interpolationDelay;
        private int interpolationDuration;
        private int teleportDuration;

        private DisplayOptions build() {
            return new DisplayOptions(this);
        }
    }
}
