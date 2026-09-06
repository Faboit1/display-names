package com.faboit.displaynames.config;

import com.faboit.displaynames.StubPlayer;
import com.faboit.displaynames.text.PlaceholderResolver;
import com.faboit.displaynames.text.Placeholders;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profiles gated on conditions - the half of the feature that makes <em>settings</em> per-player.
 *
 * <p>{@link ConditionsTest} covers the grammar; what matters here is that a condition selects a
 * whole profile, that a profile with no lines of its own can exist purely to change appearance,
 * and that the two shapes which would silently do nothing are refused at load time.
 */
class ConditionalProfileTest {

    private static final Logger LOUD = Logger.getLogger("test");
    private static final float EPSILON = 1.0E-5F;

    /** Used where a warning is the point of the test, so the run does not print it. */
    private static Logger quiet() {
        Logger logger = Logger.getLogger("displaynames-profile-test");
        logger.setUseParentHandlers(false);
        return logger;
    }

    private static Settings load(String yaml, Logger logger) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception ex) {
            throw new AssertionError("fixture is not valid YAML", ex);
        }
        return Settings.load(config, Settings.createRenderer(config, logger), logger);
    }

    private static Settings load(String yaml) {
        return load(yaml, LOUD);
    }

    private static PlaceholderResolver resolver(Map<String, String> values) {
        return (player, text) -> Placeholders.replace(text, values::get);
    }

    private static final PlaceholderResolver EMPTY = resolver(Map.of());

    // ------------------------------------------------------------- condition-gated selection

    @Test
    void aConditionPicksTheProfileAndTheDefaultTakesOverWhenItStopsMatching() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                conditions:
                  in_combat:
                    conditions:
                      - "%combat%=true"
                profiles:
                  combat:
                    condition: in_combat
                    lines:
                      - "<red>%player_name%"
                """);
        Player player = StubPlayer.named("Steve");

        assertEquals("combat",
                settings.profileFor(player, resolver(Map.of("combat", "true"))).id());
        assertEquals("default",
                settings.profileFor(player, resolver(Map.of("combat", "false"))).id());
    }

    @Test
    void anInlineConditionNeedsNoDeclaredEntry() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  lobby:
                    condition: "%server%=lobby"
                    lines:
                      - "<gray>%player_name%"
                """);
        Player player = StubPlayer.named("Steve");

        assertEquals("lobby", settings.profileFor(player, resolver(Map.of("server", "lobby"))).id());
        assertEquals("default", settings.profileFor(player, resolver(Map.of("server", "survival"))).id());
    }

    @Test
    void aProfileWithBothGatesNeedsBothOfThem() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  staff_afk:
                    permission: displaynames.staff
                    condition: "%afk%=yes"
                    lines:
                      - "<gray>%player_name%"
                """);
        Player staff = StubPlayer.named("Steve", "displaynames.staff");
        Player guest = StubPlayer.named("Alex");

        assertEquals("staff_afk", settings.profileFor(staff, resolver(Map.of("afk", "yes"))).id());
        assertEquals("default", settings.profileFor(staff, resolver(Map.of("afk", "no"))).id());
        assertEquals("default", settings.profileFor(guest, resolver(Map.of("afk", "yes"))).id());
    }

    @Test
    void priorityDecidesWhenSeveralConditionsMatchAtOnce() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  low:
                    priority: 1
                    condition: "%state%=busy"
                    lines:
                      - "<gray>%player_name%"
                  high:
                    priority: 10
                    condition: "%state%=busy"
                    lines:
                      - "<red>%player_name%"
                """);
        assertEquals("high",
                settings.profileFor(StubPlayer.named("Steve"), resolver(Map.of("state", "busy"))).id());
    }

    @Test
    void aConditionOnAnUnresolvedPlaceholderSimplyDoesNotMatch() {
        // Without PlaceholderAPI the token comes back literal, so `%afk%=yes` compares "%afk%"
        // against "yes". It must fall through to the default rather than throw or match.
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  afk:
                    condition: "%afk%=yes"
                    lines:
                      - "<gray>%player_name%"
                """);
        assertEquals("default", settings.profileFor(StubPlayer.named("Steve"), EMPTY).id());
    }

    // ------------------------------------------------------------- settings-only profiles

    @Test
    void aProfileWithNoLinesInheritsTheDefaultTextAndOverridesOnlyTheAppearance() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  riding:
                    condition: "%mounted%=true"
                    display:
                      scale: 1.5
                """);
        Profile profile = settings.profileFor(StubPlayer.named("Steve"),
                resolver(Map.of("mounted", "true")));

        assertEquals("riding", profile.id());
        assertSame(settings.defaultProfile().template(), profile.template(),
                "a lineless profile should render nametag.lines, not a template of its own");
        assertNotSame(settings.display(), profile.display());
        assertEquals(1.5F, profile.display().scale().x(), EPSILON);
    }

    @Test
    void aProfileWithNoLinesCanOverrideJustTheOffset() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  riding:
                    condition: "%mounted%=true"
                    offset:
                      y: 3.2
                """);
        Profile profile = settings.profileFor(StubPlayer.named("Steve"),
                resolver(Map.of("mounted", "true")));

        assertEquals("riding", profile.id());
        assertEquals(3.2F, profile.display().offset().y(), EPSILON);
    }

    // ------------------------------------------------------------- refused shapes

    @Test
    void aProfileWithNeitherPermissionNorConditionIsSkipped() {
        // It would match everybody and quietly replace nametag.lines for the whole server.
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  everyone:
                    lines:
                      - "<red>%player_name%"
                """, quiet());
        assertTrue(settings.profiles().isEmpty());
    }

    @Test
    void aProfileWithNoLinesAndNoAppearanceOverrideIsSkipped() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  pointless:
                    condition: "%afk%=yes"
                """, quiet());
        assertTrue(settings.profiles().isEmpty());
    }

    @Test
    void aProfileWhoseConditionCannotBeParsedFallsBackToItsPermission() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                profiles:
                  staff:
                    permission: displaynames.staff
                    condition: nonsense
                    lines:
                      - "<gold>%player_name%"
                """, quiet());

        assertEquals(1, settings.profiles().size());
        assertEquals("staff", settings.profileFor(
                StubPlayer.named("Steve", "displaynames.staff"), EMPTY).id());
        assertEquals("default", settings.profileFor(StubPlayer.named("Alex"), EMPTY).id());
    }

    // ------------------------------------------------------------- visibility

    @Test
    void theHideConditionIsLoadedAndAnsweredPerPlayer() {
        Settings settings = load("""
                nametag:
                  lines:
                    - "<white>%player_name%"
                conditions:
                  vanished:
                    conditions:
                      - "%vanished%=true"
                visibility:
                  hide-condition: vanished
                """);
        Player player = StubPlayer.named("Steve");

        assertNotNull(settings.hideCondition());
        assertTrue(settings.hideCondition().matches(player, settings.conditions(),
                resolver(Map.of("vanished", "true")), 0));
        assertFalse(settings.hideCondition().matches(player, settings.conditions(),
                resolver(Map.of("vanished", "false")), 0));
    }
}
