package com.faboit.displaynames.condition;

import com.faboit.displaynames.text.PlaceholderResolver;
import org.bukkit.entity.Player;

/** A single test inside a condition: either a comparison or a permission lookup. */
sealed interface Check {

    /**
     * @param depth how many condition references deep this evaluation already is; threaded
     *              through rather than kept in a thread local because these run on whichever
     *              region thread owns the player
     */
    boolean test(Player player, Conditions registry, PlaceholderResolver base, int depth);

    /** {@code %placeholder% <operator> value}. */
    record Comparison(Operand left, Operator operator, Operand right) implements Check {

        @Override
        public boolean test(Player player, Conditions registry, PlaceholderResolver base, int depth) {
            return operator.matches(left.value(player, registry, base, depth),
                    right.value(player, registry, base, depth));
        }
    }

    /** {@code permission:node}, or {@code !permission:node} for the negated form. */
    record Permission(String node, boolean negated) implements Check {

        @Override
        public boolean test(Player player, Conditions registry, PlaceholderResolver base, int depth) {
            return player.hasPermission(node) != negated;
        }
    }
}
