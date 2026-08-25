package com.faboit.displaynames.text;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BuiltinPlaceholdersTest {

    private static final Function<String, String> LOOKUP =
            Map.of("a", "1", "b", "2", "player_name", "Steve")::get;

    @Test
    void textWithoutMarkersIsReturnedUnchanged() {
        String input = "<yellow>no placeholders here";
        assertSame(input, BuiltinPlaceholders.replace(input, LOOKUP));
    }

    @Test
    void nothingRecognisedLeavesTheStringAlone() {
        String input = "%luckperms_prefix%%vault_eco_balance%";
        assertSame(input, BuiltinPlaceholders.replace(input, LOOKUP));
    }

    @Test
    void aSingleTokenIsSubstituted() {
        assertEquals("<white>Steve", BuiltinPlaceholders.replace("<white>%player_name%", LOOKUP));
    }

    @Test
    void adjacentTokensBothResolve() {
        // The closing marker of one placeholder opens the next, which the scan has to handle.
        assertEquals("12", BuiltinPlaceholders.replace("%a%%b%", LOOKUP));
    }

    @Test
    void unknownTokensAreSkippedWithoutEatingLaterOnes() {
        assertEquals("%unknown% and 1", BuiltinPlaceholders.replace("%unknown% and %a%", LOOKUP));
    }

    @Test
    void anUnknownTokenBetweenTwoKnownOnesStillLeavesBothResolved() {
        assertEquals("1%nope%2", BuiltinPlaceholders.replace("%a%%nope%%b%", LOOKUP));
    }

    @Test
    void surroundingTextIsPreserved() {
        assertEquals("[1] middle [2] tail",
                BuiltinPlaceholders.replace("[%a%] middle [%b%] tail", LOOKUP));
    }

    @Test
    void anUnclosedMarkerIsLeftAsIs() {
        assertEquals("50% off", BuiltinPlaceholders.replace("50% off", LOOKUP));
        assertEquals("1 then %dangling", BuiltinPlaceholders.replace("%a% then %dangling", LOOKUP));
    }

    @Test
    void anEmptyTokenIsNotMistakenForAPlaceholder() {
        assertEquals("%%", BuiltinPlaceholders.replace("%%", LOOKUP));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals(null, BuiltinPlaceholders.replace(null, LOOKUP));
        assertEquals("", BuiltinPlaceholders.replace("", LOOKUP));
    }
}
