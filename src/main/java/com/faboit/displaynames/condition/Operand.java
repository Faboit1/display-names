package com.faboit.displaynames.condition;

import com.faboit.displaynames.text.PlaceholderResolver;
import org.bukkit.entity.Player;

/**
 * One side of a comparison.
 *
 * <p>Whether the text needs resolving at all is decided once, at load: the overwhelming majority
 * of right-hand sides are literals like {@code true} or {@code 100}, and a literal never reaches
 * PlaceholderAPI on the refresh path.
 *
 * @param text    the source text, trimmed
 * @param dynamic {@code true} when the text contains a {@code %...%} pair worth resolving
 */
record Operand(String text, boolean dynamic) {

    static Operand of(String source) {
        String trimmed = source.trim();
        int first = trimmed.indexOf('%');
        boolean dynamic = first >= 0 && trimmed.indexOf('%', first + 1) > first + 1;
        return new Operand(trimmed, dynamic);
    }

    /**
     * The value to compare, with placeholders and {@code %condition:name%} references resolved.
     *
     * <p>Trimmed afterwards as well, because a placeholder that pads its output would otherwise
     * make {@code %some_rank% = owner} quietly false.
     */
    String value(Player player, Conditions registry, PlaceholderResolver base, int depth) {
        if (!dynamic) return text;
        return registry.resolveAt(player, text, base, depth).trim();
    }
}
