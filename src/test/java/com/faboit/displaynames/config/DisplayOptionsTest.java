package com.faboit.displaynames.config;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayOptionsTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final float EPSILON = 1.0E-5F;

    private static ConfigurationSection yaml(String source) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(source);
        } catch (Exception ex) {
            throw new AssertionError("test fixture is not valid YAML", ex);
        }
        return config;
    }

    private static DisplayOptions load(String source) {
        ConfigurationSection root = yaml(source);
        return DisplayOptions.load(root.getConfigurationSection("display"),
                root.getConfigurationSection("offset"), DisplayOptions.defaults(), LOGGER);
    }

    @Test
    void anAbsentBlockInheritsTheParentInstanceItself() {
        // Identity matters: the handle decides whether to rebuild a player's entity by comparing
        // DisplayOptions instances, so inheriting must not produce an equal-but-different object.
        DisplayOptions parent = DisplayOptions.defaults();
        assertSame(parent, DisplayOptions.load(null, null, parent, LOGGER));
    }

    @Test
    void aProfileInheritsEverythingItDoesNotMention() {
        DisplayOptions parent = load("""
                display:
                  line-width: 320
                  see-through: true
                  view-range: 0.5
                """);
        ConfigurationSection profile = yaml("""
                display:
                  scale: 2.0
                """);

        DisplayOptions child = DisplayOptions.load(profile.getConfigurationSection("display"),
                null, parent, LOGGER);

        assertEquals(2.0F, child.scale().x(), EPSILON);
        assertEquals(320, child.lineWidth());
        assertTrue(child.seeThrough());
        assertEquals(0.5F, child.viewRange(), EPSILON);
    }

    @Test
    void theOffsetIsKeptRawForPositioningAFollowedTag() {
        // FOLLOW puts this straight into the entity's position, so it must survive unmodified.
        Vector3f offset = load("""
                offset:
                  x: 1.0
                  y: 2.5
                  z: -1.0
                """).offset();
        assertEquals(1.0F, offset.x(), EPSILON);
        assertEquals(2.5F, offset.y(), EPSILON);
        assertEquals(-1.0F, offset.z(), EPSILON);
    }

    @Test
    void mountingCompensatesTheAnchorBecauseAPassengerStartsAtChestHeight() {
        Vector3f translation = load("""
                offset:
                  x: 1.0
                  y: 2.5
                  z: -1.0
                """).mountTranslation();
        assertEquals(1.0F, translation.x(), EPSILON);
        assertEquals(2.5F - DisplayOptions.DEFAULT_MOUNT_ANCHOR, translation.y(), EPSILON);
        assertEquals(-1.0F, translation.z(), EPSILON);
    }

    @Test
    void aCustomMountAnchorShiftsTheTranslation() {
        DisplayOptions options = load("""
                offset:
                  y: 2.5
                  mount-anchor: 1.0
                """);
        assertEquals(1.5F, options.translation().y(), EPSILON);
    }

    @Test
    void scaleAcceptsBothAScalarAndPerAxis() {
        assertEquals(new Vector3f(1.5F, 1.5F, 1.5F), load("""
                display:
                  scale: 1.5
                """).scale());

        assertEquals(new Vector3f(2.0F, 3.0F, 1.0F), load("""
                display:
                  scale:
                    x: 2.0
                    y: 3.0
                    z: 1.0
                """).scale());
    }

    @Test
    void aNonPositiveScaleFallsBackInsteadOfCollapsingTheText() {
        assertEquals(1.0F, load("""
                display:
                  scale: 0.0
                """).scale().x(), EPSILON);
    }

    @Test
    void backgroundModesMapToTheRightArgb() {
        assertNull(load("""
                display:
                  background:
                    mode: CLIENT
                """).background(), "CLIENT must be null so the client's own opacity setting applies");

        Color none = load("""
                display:
                  background:
                    mode: NONE
                """).background();
        assertEquals(0, none.getAlpha());

        Color custom = load("""
                display:
                  background:
                    mode: CUSTOM
                    color: "#204060"
                    opacity: 128
                """).background();
        assertEquals(128, custom.getAlpha());
        assertEquals(0x20, custom.getRed());
        assertEquals(0x40, custom.getGreen());
        assertEquals(0x60, custom.getBlue());
    }

    @Test
    void backgroundStillAcceptsTheOlderStringForm() {
        assertNull(load("""
                display:
                  background: "default"
                """).background());

        assertEquals(0, load("""
                display:
                  background: "transparent"
                """).background().getAlpha());

        Color hex = load("""
                display:
                  background: "#80112233"
                """).background();
        assertEquals(0x80, hex.getAlpha());
        assertEquals(0x11, hex.getRed());
    }

    @Test
    void opacityUsesTheVanillaOpaqueSentinelAndClampsTheRest() {
        assertEquals((byte) -1, load("""
                display:
                  text-opacity: 255
                """).textOpacity());

        assertEquals((byte) 128, load("""
                display:
                  text-opacity: 128
                """).textOpacity());

        // 0 is rejected by the server, so it clamps to the lowest legal value.
        assertEquals((byte) 1, load("""
                display:
                  text-opacity: 0
                """).textOpacity());
    }

    @Test
    void billboardAndAlignmentAreCaseInsensitive() {
        DisplayOptions options = load("""
                display:
                  billboard: fixed
                  alignment: left
                """);
        assertEquals(Display.Billboard.FIXED, options.billboard());
        assertEquals(TextDisplay.TextAlignment.LEFT, options.alignment());
    }

    @Test
    void anUnknownEnumKeepsTheInheritedValue() {
        DisplayOptions options = load("""
                display:
                  billboard: sideways
                """);
        assertEquals(Display.Billboard.CENTER, options.billboard());
    }

    @Test
    void yawFollowsMinecraftsClockwiseConvention() {
        // Minecraft yaw 90 faces west (-X). Starting from the default facing of south (+Z),
        // that is a -90 degree turn about +Y in JOML's right-handed frame.
        Quaternionf rotation = load("""
                display:
                  rotation:
                    yaw: 90.0
                """).leftRotation();

        Vector3f facing = rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
        assertEquals(-1.0F, facing.x(), 1.0E-4F);
        assertEquals(0.0F, facing.y(), 1.0E-4F);
        assertEquals(0.0F, facing.z(), 1.0E-4F);
    }

    @Test
    void noRotationBlockLeavesTheOrientationAlone() {
        assertEquals(new Quaternionf(), load("""
                display:
                  billboard: FIXED
                """).leftRotation());
    }

    @Test
    void brightnessCanBeDisabledToUseWorldLighting() {
        assertNull(load("""
                display:
                  brightness:
                    enabled: false
                """).brightness(), "disabled brightness must be null so world lighting applies");

        assertNull(load("""
                display:
                  brightness: false
                """).brightness());

        Display.Brightness explicit = load("""
                display:
                  brightness:
                    block: 7
                    sky: 3
                """).brightness();
        assertNotNull(explicit);
        assertEquals(7, explicit.getBlockLight());
        assertEquals(3, explicit.getSkyLight());
    }

    @Test
    void brightnessLevelsAreClampedToTheLegalRange() {
        Display.Brightness brightness = load("""
                display:
                  brightness:
                    block: 99
                    sky: -4
                """).brightness();
        assertEquals(15, brightness.getBlockLight());
        assertEquals(0, brightness.getSkyLight());
    }

    @Test
    void glowColourIsOffUnlessSet() {
        assertNull(load("display:\n  line-width: 100\n").glowColor());
        assertNull(load("""
                display:
                  glow-color: "none"
                """).glowColor());
        assertEquals(Color.fromRGB(0xFF, 0x55, 0x55), load("""
                display:
                  glow-color: "#ff5555"
                """).glowColor());
    }

    @Test
    void teleportDurationIsClampedToWhatTheProtocolAllows() {
        assertEquals(59, load("""
                display:
                  interpolation:
                    teleport-duration: 500
                """).teleportDuration());
    }

    @Test
    void entityShadowDefaultsToOff() {
        assertEquals(0.0F, DisplayOptions.defaults().shadowRadius(), EPSILON);
        assertEquals(2.5F, load("""
                display:
                  entity-shadow:
                    radius: 2.5
                """).shadowRadius(), EPSILON);
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        DisplayOptions defaults = DisplayOptions.defaults();
        assertEquals(Display.Billboard.CENTER, defaults.billboard());
        assertEquals(TextDisplay.TextAlignment.CENTER, defaults.alignment());
        assertEquals(200, defaults.lineWidth());
        assertEquals(1.0F, defaults.viewRange(), EPSILON);
        assertEquals((byte) -1, defaults.textOpacity());
        assertFalse(defaults.textShadow());
        assertFalse(defaults.seeThrough());
        assertEquals(0, defaults.background().getAlpha());
        assertEquals(2.5F - DisplayOptions.DEFAULT_MOUNT_ANCHOR, defaults.translation().y(), EPSILON);
    }
}
