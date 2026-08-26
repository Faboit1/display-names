package com.faboit.displaynames.config;

import com.faboit.displaynames.nametag.TeamGuard;
import com.faboit.displaynames.text.TextRenderer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the config.yml that actually ships in the jar and runs it through the real parser.
 *
 * <p>This is the file every install starts from, so a mistake in it reaches users directly
 * without any code being wrong.
 */
class ShippedConfigTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final float EPSILON = 1.0E-5F;

    private static Settings shipped() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new File("src/main/resources/config.yml"));
        } catch (Exception ex) {
            throw new AssertionError("the shipped config.yml does not load", ex);
        }
        return Settings.load(config, Settings.createRenderer(config, LOGGER), LOGGER);
    }

    @Test
    void theShippedConfigParses() {
        assertNotNull(shipped());
    }

    @Test
    void noProfileIsActiveOutOfTheBox() {
        // Profile permissions are registered with a default of false, but a profile that is
        // merely *present* still costs a hasPermission check per refresh, and an example that
        // silently outranks the nametag someone configured is exactly the trap this guards.
        // Keep the examples in config.yml commented out.
        assertTrue(shipped().profiles().isEmpty(),
                "the example profiles must ship commented out so a fresh install renders "
                        + "nametag.lines and nothing else");
    }

    @Test
    void theDefaultNametagStillUsesPlaceholders() {
        assertTrue(shipped().defaultProfile().template().dynamic(),
                "the shipped nametag.lines should demonstrate placeholders");
    }

    @Test
    void theDocumentedDefaultsAreWhatTheFileActuallySays() {
        Settings settings = shipped();
        assertEquals(10, settings.refreshInterval());
        assertTrue(settings.autoRefresh());
        assertTrue(settings.hideFromSelf());
        // Ships OFF: it is a real saving, but it is the one setting that can make a tag look
        // wrong rather than merely slow, and correctness comes first out of the box.
        assertFalse(settings.skipWithoutViewers());
        assertEquals(128, settings.viewerGridSize());
        assertEquals(512, settings.componentCacheSize());
    }

    @Test
    void vanillaNametagHidingDefaultsToTheTabSafeMode() {
        Settings settings = shipped();
        assertEquals(TeamGuard.Mode.ADOPT, settings.teamMode());
        assertEquals("displaynames", settings.teamName());
        assertEquals(20L, settings.teamReassertInterval());
    }

    @Test
    void tagsAreVisibleThroughTerrainByDefault() {
        // Requested behaviour: readable underground, with sneaking and invisibility dropping
        // back to line-of-sight only rather than hiding the tag outright.
        Settings settings = shipped();
        assertTrue(settings.display().seeThrough());
        assertFalse(settings.seeThroughWhileSneaking());
        assertFalse(settings.seeThroughWhileInvisible());
        assertFalse(settings.hideWhileSneaking());
        assertFalse(settings.hideWhileInvisible());
    }

    @Test
    void theDefaultTagRidesItsPlayerSoItKeepsUpInMotion() {
        Settings settings = shipped();
        DisplayOptions display = settings.display();
        assertEquals(Display.Billboard.CENTER, display.billboard());

        // Ships MOUNT after both modes were tried on a live server. FOLLOW is geometrically
        // exact - no transformation, so a CENTER billboard pivots about the text itself and the
        // tag sits dead centre above the head from every angle - but the server has to move it,
        // from a position already a tick old, so it visibly trails a moving player. MOUNT hands
        // the carrying to the client, which is perfect at any speed, and pays for it with the
        // drift a translated CENTER billboard has at steep angles. Smooth-and-slightly-off beat
        // exact-and-laggy in practice, which is the call this assertion is pinning down.
        assertEquals(Anchor.MOUNT, settings.anchor());
        assertEquals(1L, settings.followInterval());
        assertEquals(2.5F, display.offset().y(), EPSILON);

        assertTrue(display.leftRotation().equals(0.0F, 0.0F, 0.0F, 1.0F),
                "the shipped rotation should be identity");
    }

    @Test
    void mountingLeavesJustTheHeightAboveTheAnchorAsATransformation() {
        // The whole cost of MOUNT is this vector: it is what a CENTER billboard swings around.
        // If it ever grows, the drift grows with it, so the shipped pair is worth pinning.
        DisplayOptions display = shipped().display();
        assertEquals(0.0F, display.mountTranslation().x(), EPSILON);
        assertEquals(2.5F - DisplayOptions.DEFAULT_MOUNT_ANCHOR, display.mountTranslation().y(), EPSILON);
        assertEquals(0.0F, display.mountTranslation().z(), EPSILON);
    }

    @Test
    void theOldBooleanFormOfHideVanillaNametagStillWorks() {
        // Configs written for 1.0 are still out there and must not silently stop hiding plates.
        YamlConfiguration legacy = new YamlConfiguration();
        try {
            legacy.loadFromString("""
                    nametag:
                      lines:
                        - "<white>%player_name%"
                    visibility:
                      hide-vanilla-nametag: true
                    """);
        } catch (Exception ex) {
            throw new AssertionError("fixture is not valid YAML", ex);
        }
        Settings settings = Settings.load(legacy, Settings.createRenderer(legacy, LOGGER), LOGGER);
        assertEquals(TeamGuard.Mode.ADOPT, settings.teamMode());

        YamlConfiguration off = new YamlConfiguration();
        try {
            off.loadFromString("""
                    nametag:
                      lines:
                        - "<white>%player_name%"
                    visibility:
                      hide-vanilla-nametag: false
                    """);
        } catch (Exception ex) {
            throw new AssertionError("fixture is not valid YAML", ex);
        }
        assertEquals(TeamGuard.Mode.NONE,
                Settings.load(off, Settings.createRenderer(off, LOGGER), LOGGER).teamMode());
    }
}
