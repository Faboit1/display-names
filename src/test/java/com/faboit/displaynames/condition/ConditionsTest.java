package com.faboit.displaynames.condition;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The condition parser and evaluator.
 *
 * <p>Most of what can go wrong here is parsing rather than logic: {@code |} is both the OR
 * separator and half of two operators, placeholder names are full of operator characters, and
 * {@code !=} contains {@code =}. Those cases carry the bulk of the tests.
 */
class ConditionsTest {

    private static final Logger LOUD = Logger.getLogger("test");

    /** Used where a warning is the point of the test, so the run does not print it. */
    private static Logger quiet() {
        Logger logger = Logger.getLogger("displaynames-condition-test");
        logger.setUseParentHandlers(false);
        return logger;
    }

    private static Conditions load(String yaml, Logger logger) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception ex) {
            throw new AssertionError("fixture is not valid YAML", ex);
        }
        return Conditions.load(config.getConfigurationSection("conditions"), logger);
    }

    private static Conditions load(String yaml) {
        return load(yaml, LOUD);
    }

    private static PlaceholderResolver resolver(Map<String, String> values) {
        return (player, text) -> Placeholders.replace(text, values::get);
    }

    private static final PlaceholderResolver EMPTY = resolver(Map.of());

    // ---------------------------------------------------------------- block form

    @Test
    void aBlockConditionEvaluatesItsChecksAndPicksTheMatchingOutput() {
        Conditions conditions = load("""
                conditions:
                  is_afk:
                    conditions:
                      - "%afk%=yes"
                    true: "<gray>[AFK]"
                    false: ""
                """);
        Player player = StubPlayer.named("Steve");

        assertEquals("<gray>[AFK]", conditions.expand(player, "%condition:is_afk%",
                resolver(Map.of("afk", "yes"))));
        assertEquals("", conditions.expand(player, "%condition:is_afk%",
                resolver(Map.of("afk", "no"))));
    }

    @Test
    void unquotedYamlBooleanKeysStillReadAsTheTrueAndFalseOutputs() {
        // `true:` and `false:` are YAML booleans, not strings. Bukkit keys its map by toString(),
        // so they land under "true"/"false" - but that is a detail worth pinning rather than
        // assuming, because getting it wrong silently gives every condition the word "true".
        Conditions conditions = load("""
                conditions:
                  plain:
                    conditions: ["%x%=1"]
                    true: yes-branch
                    false: no-branch
                """);
        Player player = StubPlayer.named("Steve");
        assertEquals("yes-branch", conditions.expand(player, "%condition:plain%", resolver(Map.of("x", "1"))));
        assertEquals("no-branch", conditions.expand(player, "%condition:plain%", resolver(Map.of("x", "2"))));
    }

    @Test
    void withoutOutputsAConditionExpandsToTrueOrFalse() {
        Conditions conditions = load("""
                conditions:
                  simple:
                    conditions: ["%x%=1"]
                """);
        Player player = StubPlayer.named("Steve");
        assertEquals("true", conditions.expand(player, "%condition:simple%", resolver(Map.of("x", "1"))));
        assertEquals("false", conditions.expand(player, "%condition:simple%", resolver(Map.of("x", "9"))));
    }

    @Test
    void andNeedsEveryCheckWhileOrNeedsOne() {
        String template = """
                conditions:
                  combined:
                    conditions:
                      - "%a%=1"
                      - "%b%=2"
                    type: %TYPE%
                """;
        Conditions and = load(template.replace("%TYPE%", "AND"));
        Conditions or = load(template.replace("%TYPE%", "OR"));
        Player player = StubPlayer.named("Steve");

        PlaceholderResolver half = resolver(Map.of("a", "1", "b", "nope"));
        PlaceholderResolver both = resolver(Map.of("a", "1", "b", "2"));

        assertFalse(and.matches(player, and.named("combined"), half));
        assertTrue(and.matches(player, and.named("combined"), both));
        assertTrue(or.matches(player, or.named("combined"), half));
        assertFalse(or.matches(player, or.named("combined"), resolver(Map.of("a", "x", "b", "y"))));
    }

    @Test
    void aConditionCanBeWrittenAsOneLineOrAsAList() {
        Conditions conditions = load("""
                conditions:
                  short: "%a%=1"
                  listed:
                    - "%a%=1"
                    - "%b%=2"
                """);
        Player player = StubPlayer.named("Steve");
        assertNotNull(conditions.named("short"));
        assertNotNull(conditions.named("listed"));
        assertEquals(2, conditions.named("listed").size());
        assertTrue(conditions.matches(player, conditions.named("listed"), resolver(Map.of("a", "1", "b", "2"))));
    }

    // ---------------------------------------------------------------- parsing hazards

    @Test
    void notEqualsIsNotReadAsEquals() {
        Check.Comparison check = (Check.Comparison) Conditions.parseCheck("%rank%!=default");
        assertEquals(Operator.NOT_EQUALS, check.operator());
        assertEquals("%rank%", check.left().text());
        assertEquals("default", check.right().text());
    }

    @Test
    void theThreeCharacterNegationsBeatTheirTwoCharacterHalves() {
        assertEquals(Operator.NOT_CONTAINS, comparison("%a%!<-b").operator());
        assertEquals(Operator.NOT_STARTS_WITH, comparison("%a%!|-b").operator());
        assertEquals(Operator.NOT_ENDS_WITH, comparison("%a%!-|b").operator());
        assertEquals(Operator.CONTAINS, comparison("%a%<-b").operator());
        assertEquals(Operator.STARTS_WITH, comparison("%a%|-b").operator());
        assertEquals(Operator.ENDS_WITH, comparison("%a%-|b").operator());
        assertEquals(Operator.GREATER_OR_EQUAL, comparison("%a%>=1").operator());
        assertEquals(Operator.LESS_OR_EQUAL, comparison("%a%<=1").operator());
    }

    @Test
    void anOperatorCharacterInsideAPlaceholderNameIsNotAnOperator() {
        // %luckperms_meta_rank-name% ends in a dash; without the marker skip, "-|" or "=" inside
        // a placeholder would split the check in the wrong place and compare nonsense.
        Check.Comparison check = comparison("%rank-name%=owner");
        assertEquals("%rank-name%", check.left().text());
        assertEquals("owner", check.right().text());

        Player player = StubPlayer.named("Steve");
        Conditions conditions = Conditions.NONE;
        assertTrue(check.test(player, conditions, resolver(Map.of("rank-name", "owner")), 0));
    }

    @Test
    void spacesAroundAnOperatorAreIgnored() {
        Check.Comparison check = comparison("%rank% = owner ");
        assertEquals("%rank%", check.left().text());
        assertEquals("owner", check.right().text());
    }

    @Test
    void aPlaceholderThatPadsItsOutputStillCompares() {
        Check.Comparison check = comparison("%rank%=owner");
        assertTrue(check.test(StubPlayer.named("Steve"), Conditions.NONE,
                resolver(Map.of("rank", "  owner ")), 0));
    }

    @Test
    void aBarePlaceholderAsksWhetherItSaysTrue() {
        Check.Comparison check = comparison("%vanished%");
        assertEquals(Operator.EQUALS, check.operator());
        assertTrue(check.test(StubPlayer.named("Steve"), Conditions.NONE,
                resolver(Map.of("vanished", "true")), 0));

        Check.Comparison negated = comparison("!%vanished%");
        assertEquals(Operator.NOT_EQUALS, negated.operator());
        assertEquals("%vanished%", negated.left().text());
        assertTrue(negated.test(StubPlayer.named("Steve"), Conditions.NONE,
                resolver(Map.of("vanished", "false")), 0));
    }

    @Test
    void aPlainWordIsNotACheck() {
        assertNull(Conditions.parseCheck("just some text"));
        assertNull(Conditions.parseCheck(""));
        assertNull(Conditions.parseCheck("permission:"));
    }

    @Test
    void permissionChecksReadTheNodeAndItsNegation() {
        Check.Permission held = (Check.Permission) Conditions.parseCheck("permission:some.node");
        assertEquals("some.node", held.node());
        assertFalse(held.negated());

        Check.Permission missing = (Check.Permission) Conditions.parseCheck("!permission:some.node");
        assertTrue(missing.negated());

        Player with = StubPlayer.named("Steve", "some.node");
        Player without = StubPlayer.named("Alex");
        assertTrue(held.test(with, Conditions.NONE, EMPTY, 0));
        assertFalse(held.test(without, Conditions.NONE, EMPTY, 0));
        assertFalse(missing.test(with, Conditions.NONE, EMPTY, 0));
        assertTrue(missing.test(without, Conditions.NONE, EMPTY, 0));
    }

    // ---------------------------------------------------------------- inline expressions

    @Test
    void aSemicolonMeansAndAndABarMeansOr() {
        Condition and = Conditions.NONE.reference("%a%=1;%b%=2", "test", LOUD);
        assertEquals(2, and.size());
        assertTrue(and.requireAll());

        Condition or = Conditions.NONE.reference("%a%=1|%b%=2", "test", LOUD);
        assertEquals(2, or.size());
        assertFalse(or.requireAll());

        Player player = StubPlayer.named("Steve");
        PlaceholderResolver half = resolver(Map.of("a", "1", "b", "x"));
        assertFalse(Conditions.NONE.matches(player, and, half));
        assertTrue(Conditions.NONE.matches(player, or, half));
    }

    @Test
    void theOrSeparatorDoesNotEatTheStartsWithAndEndsWithOperators() {
        // "|-" and "-|" both contain the OR separator; splitting on it would leave two
        // meaningless halves and a condition that never matches.
        Condition startsWith = Conditions.NONE.reference("%world%|-world_", "test", LOUD);
        assertEquals(1, startsWith.size());
        Condition endsWith = Conditions.NONE.reference("%world%-|_nether", "test", LOUD);
        assertEquals(1, endsWith.size());

        Player player = StubPlayer.named("Steve");
        assertTrue(Conditions.NONE.matches(player, startsWith, resolver(Map.of("world", "world_nether"))));
        assertTrue(Conditions.NONE.matches(player, endsWith, resolver(Map.of("world", "world_nether"))));
    }

    @Test
    void aReferenceTakesADeclaredNameOverAnInlineExpression() {
        Conditions conditions = load("""
                conditions:
                  vip:
                    conditions: ["permission:rank.vip"]
                """);
        assertSame(conditions.named("vip"), conditions.reference("vip", "test", LOUD));
        assertSame(conditions.named("vip"), conditions.reference("VIP", "test", LOUD));
        // %condition:name% is how a reference is spelled in text, so it is accepted here too.
        assertSame(conditions.named("vip"), conditions.reference("%condition:vip%", "test", LOUD));
    }

    @Test
    void anUnusableReferenceIsIgnoredRatherThanGuessedAt() {
        assertNull(Conditions.NONE.reference(null, "test", quiet()));
        assertNull(Conditions.NONE.reference("   ", "test", quiet()));
        assertNull(Conditions.NONE.reference("typo_in_the_name", "test", quiet()));
    }

    // ---------------------------------------------------------------- references between conditions

    @Test
    void oneConditionCanBeBuiltOnAnother() {
        Conditions conditions = load("""
                conditions:
                  is_staff:
                    conditions: ["permission:rank.staff"]
                  badge:
                    conditions: ["%condition:is_staff%=true"]
                    true: "[STAFF]"
                    false: ""
                """);
        assertEquals("[STAFF]", conditions.expand(StubPlayer.named("Steve", "rank.staff"),
                "%condition:badge%", EMPTY));
        assertEquals("", conditions.expand(StubPlayer.named("Alex"), "%condition:badge%", EMPTY));
    }

    @Test
    void aReferenceInsideAnOutputIsExpandedToo() {
        Conditions conditions = load("""
                conditions:
                  inner:
                    conditions: ["%a%=1"]
                    true: "INNER"
                    false: "-"
                  outer:
                    conditions: ["%b%=2"]
                    true: "<%condition:inner%>"
                    false: ""
                """);
        assertEquals("<INNER>", conditions.expand(StubPlayer.named("Steve"), "%condition:outer%",
                resolver(Map.of("a", "1", "b", "2"))));
    }

    @Test
    void aConditionThatCouldEvaluateItselfIsDroppedAtLoad() {
        Conditions direct = load("""
                conditions:
                  loop:
                    conditions: ["%condition:loop%=true"]
                """, quiet());
        assertTrue(direct.isEmpty());

        Conditions indirect = load("""
                conditions:
                  a:
                    conditions: ["%condition:b%=true"]
                  b:
                    conditions: ["%condition:a%=true"]
                  fine:
                    conditions: ["%x%=1"]
                """, quiet());
        assertEquals(1, indirect.size());
        assertNotNull(indirect.named("fine"));
    }

    @Test
    void aVeryLongChainStopsAtTheDepthCapInsteadOfOverflowingTheStack() {
        StringBuilder yaml = new StringBuilder("conditions:\n");
        int links = 40;
        for (int i = 0; i < links; i++) {
            yaml.append("  c").append(i).append(":\n")
                    .append("    conditions: [\"%x%=1\"]\n")
                    .append("    true: \"").append(i == links - 1 ? "END" : "%condition:c" + (i + 1) + "%")
                    .append("\"\n");
        }
        Conditions conditions = load(yaml.toString());
        assertEquals(links, conditions.size());

        String expanded = conditions.expand(StubPlayer.named("Steve"), "%condition:c0%",
                resolver(Map.of("x", "1")));
        // Stopped rather than recursed: the chain is longer than the cap, so what comes back is
        // the reference it gave up on, as literal text.
        assertTrue(expanded.startsWith("%condition:c"), "unexpected expansion: " + expanded);
    }

    // ---------------------------------------------------------------- expansion and cost

    @Test
    void withNoConditionsTheTextComesBackByIdentity() {
        String template = "<white>%player_name%\n<gray>%condition:nothing%";
        assertSame(template, Conditions.NONE.expand(StubPlayer.named("Steve"), template, EMPTY));
    }

    @Test
    void anUndeclaredReferenceIsLeftAloneRatherThanBlanked() {
        Conditions conditions = load("""
                conditions:
                  known:
                    conditions: ["%x%=1"]
                """);
        assertEquals("%condition:unknown% true",
                conditions.expand(StubPlayer.named("Steve"), "%condition:unknown% %condition:known%",
                        resolver(Map.of("x", "1"))));
    }

    @Test
    void resolveExpandsConditionsBeforeHandingTheTextToThePlaceholderResolver() {
        Conditions conditions = load("""
                conditions:
                  afk:
                    conditions: ["%afk%=yes"]
                    true: " <gray>(%player_name% is AFK)"
                    false: ""
                """);
        PlaceholderResolver resolver = resolver(Map.of("afk", "yes", "player_name", "Steve"));
        assertEquals("Steve <gray>(Steve is AFK)",
                conditions.resolve(StubPlayer.named("Steve"), "%player_name%%condition:afk%", resolver));
    }

    @Test
    void aConditionWithNoUsableChecksIsDroppedRatherThanMatchingEverybody() {
        Conditions conditions = load("""
                conditions:
                  broken:
                    conditions: ["not a check at all"]
                  empty:
                    conditions: []
                """, quiet());
        assertTrue(conditions.isEmpty());
    }

    private static Check.Comparison comparison(String source) {
        Check check = Conditions.parseCheck(source);
        assertNotNull(check, "did not parse: " + source);
        return (Check.Comparison) check;
    }
}
