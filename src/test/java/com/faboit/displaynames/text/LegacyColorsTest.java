package com.faboit.displaynames.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LegacyColorsTest {

    @Test
    void returnsTheSameInstanceWhenThereIsNothingToConvert() {
        String input = "<yellow>Steve <gradient:black:white>rocks</gradient>";
        assertSame(input, LegacyColors.convert(input, LegacyColors.Mode.BOTH));
    }

    @Test
    void leavesAmpersandsAloneInSectionMode() {
        String input = "Tom &c& Jerry";
        assertSame(input, LegacyColors.convert(input, LegacyColors.Mode.SECTION));
    }

    @Test
    void convertsColourAndFormatCodes() {
        assertEquals("<red><bold>Owner<reset> ",
                LegacyColors.convert("§c§lOwner§r ", LegacyColors.Mode.SECTION));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals("<gold><bold>x", LegacyColors.convert("&6&Lx", LegacyColors.Mode.AMPERSAND));
    }

    @Test
    void convertsTheHexSequence() {
        String legacy = "§x§f§f§d§d§e§eGold";
        assertEquals("<#ffddee>Gold", LegacyColors.convert(legacy, LegacyColors.Mode.SECTION));
    }

    @Test
    void keepsTrailingAndUnknownMarkers() {
        assertEquals("50% off§", LegacyColors.convert("50% off§", LegacyColors.Mode.SECTION));
        assertEquals("§z stays", LegacyColors.convert("§z stays", LegacyColors.Mode.SECTION));
    }

    @Test
    void aTruncatedHexSequenceDegradesToSingleCodes() {
        // Not enough digits to be a hex run, so each pair is translated on its own instead.
        assertEquals("§x<white><white>", LegacyColors.convert("§x§f§f", LegacyColors.Mode.SECTION));
    }

    @Test
    void aHexRunWithABadDigitDegradesToSingleCodes() {
        assertEquals("§x<white><white><white><white><white>§z",
                LegacyColors.convert("§x§f§f§f§f§f§z", LegacyColors.Mode.SECTION));
    }

    @Test
    void noneModeIsAPassthrough() {
        String input = "§cred";
        assertSame(input, LegacyColors.convert(input, LegacyColors.Mode.NONE));
    }

    @Test
    void unknownModeNamesFallBack() {
        assertEquals(LegacyColors.Mode.SECTION, LegacyColors.Mode.parse("nonsense", LegacyColors.Mode.SECTION));
        assertEquals(LegacyColors.Mode.BOTH, LegacyColors.Mode.parse(" both ", LegacyColors.Mode.SECTION));
        assertEquals(LegacyColors.Mode.NONE, LegacyColors.Mode.parse(null, LegacyColors.Mode.NONE));
    }
}
