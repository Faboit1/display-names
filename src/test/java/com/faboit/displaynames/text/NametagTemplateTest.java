package com.faboit.displaynames.text;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NametagTemplateTest {

    private final TextRenderer renderer = new TextRenderer(Logger.getLogger("test"), LegacyColors.Mode.SECTION, 8);

    @Test
    void linesAreJoinedWithNewlinesForASingleEntity() {
        NametagTemplate template = NametagTemplate.compile(List.of("<red>one", "<blue>two"), renderer);
        assertEquals("<red>one\n<blue>two", template.raw());
    }

    @Test
    void templatesWithoutPlaceholdersAreParsedOnce() {
        NametagTemplate template = NametagTemplate.compile(List.of("<red>static"), renderer);
        assertFalse(template.dynamic());
        assertNotNull(template.fixed());
        assertEquals("static", PlainTextComponentSerializer.plainText().serialize(template.fixed()));
    }

    @Test
    void templatesWithPlaceholdersAreLeftForRuntime() {
        NametagTemplate template = NametagTemplate.compile(List.of("<red>%player_name%"), renderer);
        assertTrue(template.dynamic());
        assertNull(template.fixed());
    }

    @Test
    void aLonePercentIsNotAPlaceholder() {
        assertFalse(NametagTemplate.compile(List.of("100% cotton"), renderer).dynamic());
        assertFalse(NametagTemplate.compile(List.of("%%"), renderer).dynamic());
    }

    @Test
    void emptyConfigurationProducesABlankTemplate() {
        assertTrue(NametagTemplate.compile(List.of(), renderer).isBlank());
    }

    @Test
    void identicalStringsShareOneParsedComponent() {
        assertSame(renderer.render("<red>rank"), renderer.render("<red>rank"));
        assertEquals(1, renderer.parseCount());
        assertEquals(1, renderer.cacheHitCount());
    }

    @Test
    void legacyCodesFromPlaceholdersSurviveIntoTheComponent() {
        assertEquals("Owner Steve",
                PlainTextComponentSerializer.plainText().serialize(renderer.render("§c§lOwner §fSteve")));
    }

    @Test
    void brokenMiniMessageFallsBackInsteadOfThrowing() {
        assertNotNull(renderer.render("<gradient:not-a-colour>oops</gradient>"));
    }
}
