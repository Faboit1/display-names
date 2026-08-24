package com.faboit.displaynames.text;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * A nametag format compiled once at config load.
 *
 * <p>Lines are pre-joined into a single string because a {@code TextDisplay} renders embedded
 * newlines itself - one entity, one text update, however many lines.
 *
 * <p>A template without any {@code %placeholder%} is <em>static</em>: its component is parsed
 * once here and reused forever, so those players cost nothing at all per refresh.
 */
public final class NametagTemplate {

    private final String raw;
    private final boolean dynamic;
    private final Component fixed;

    private NametagTemplate(String raw, boolean dynamic, Component fixed) {
        this.raw = raw;
        this.dynamic = dynamic;
        this.fixed = fixed;
    }

    public static NametagTemplate compile(List<String> lines, TextRenderer renderer) {
        String raw = String.join("\n", lines);
        boolean dynamic = containsPlaceholder(raw);
        Component fixed = dynamic ? null : renderer.render(raw);
        return new NametagTemplate(raw, dynamic, fixed);
    }

    /** The joined, unresolved source string. */
    public String raw() {
        return raw;
    }

    /** {@code true} when the template has to be re-resolved per player. */
    public boolean dynamic() {
        return dynamic;
    }

    /** The pre-parsed component; only non-null when {@link #dynamic()} is {@code false}. */
    public Component fixed() {
        return fixed;
    }

    public boolean isBlank() {
        return raw.isEmpty();
    }

    /** Cheap scan for a {@code %...%} pair - no regex, no allocation. */
    private static boolean containsPlaceholder(String text) {
        int first = text.indexOf('%');
        return first >= 0 && text.indexOf('%', first + 1) > first + 1;
    }
}
