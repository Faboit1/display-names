package com.faboit.displaynames.text;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The shared {@code %token%} scan. Every placeholder path in the plugin - the built-in resolver,
 * condition operands and {@code %condition:name%} expansion - walks markers through this method.
 */
class PlaceholdersTest {

    private static final Function<String, String> LOOKUP =
            Map.of("a", "1", "b", "2", "player_name", "Steve")::get;

    @Test
    void textWithoutMarkersIsReturnedUnchanged() {
        String input = "<yellow>no placeholders here";
        assertSame(input, Placeholders.replace(input, LOOKUP));
    }

    @Test
    void nothingRecognisedLeavesTheStringAlone() {
        String input = "%luckperms_prefix%%vault_eco_balance%";
        assertSame(input, Placeholders.replace(input, LOOKUP));
    }

    @Test
    void aSingleTokenIsSubstituted() {
        assertEquals("<white>Steve", Placeholders.replace("<white>%player_name%", LOOKUP));
    }

    @Test
    void adjacentTokensBothResolve() {
        // The closing marker of one placeholder opens the next, which the scan has to handle.
        assertEquals("12", Placeholders.replace("%a%%b%", LOOKUP));
    }

    @Test
    void unknownTokensAreSkippedWithoutEatingLaterOnes() {
        assertEquals("%unknown% and 1", Placeholders.replace("%unknown% and %a%", LOOKUP));
    }

    @Test
    void anUnknownTokenBetweenTwoKnownOnesStillLeavesBothResolved() {
        assertEquals("1%nope%2", Placeholders.replace("%a%%nope%%b%", LOOKUP));
    }

    @Test
    void surroundingTextIsPreserved() {
        assertEquals("[1] middle [2] tail",
                Placeholders.replace("[%a%] middle [%b%] tail", LOOKUP));
    }

    @Test
    void anUnclosedMarkerIsLeftAsIs() {
        assertEquals("50% off", Placeholders.replace("50% off", LOOKUP));
        assertEquals("1 then %dangling", Placeholders.replace("%a% then %dangling", LOOKUP));
    }

    @Test
    void anEmptyTokenIsNotMistakenForAPlaceholder() {
        assertEquals("%%", Placeholders.replace("%%", LOOKUP));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals(null, Placeholders.replace(null, LOOKUP));
        assertEquals("", Placeholders.replace("", LOOKUP));
    }
}
