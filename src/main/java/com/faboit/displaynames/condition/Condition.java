package com.faboit.displaynames.condition;

import com.faboit.displaynames.text.PlaceholderResolver;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * A named, per-player test plus the two strings it stands for when used as text.
 *
 * <p>Modelled on TAB's conditions so an existing config carries over: a list of checks combined
 * with {@code AND} or {@code OR}, and optional {@code true}/{@code false} outputs that
 * {@code %condition:name%} expands to. Unlike TAB, a condition here can also gate a whole
 * profile - which is what makes settings, not just text, per-player.
 *
 * <p>Instances are immutable and built once at config load, so the finished object can be shared
 * across every region thread that renders a nametag.
 */
public final class Condition {

    private final String id;
    private final Check[] checks;
    private final boolean requireAll;
    private final String pass;
    private final String fail;
    /** Lowercased names of the conditions this one refers to; used to break cycles at load. */
    private final Set<String> references;

    Condition(String id, Check[] checks, boolean requireAll, String pass, String fail,
              Set<String> references) {
        this.id = id;
        this.checks = checks;
        this.requireAll = requireAll;
        this.pass = pass;
        this.fail = fail;
        this.references = references;
    }

    /** Config key, or the expression itself for a condition written inline. */
    public String id() {
        return id;
    }

    /** How many checks this condition combines. */
    public int size() {
        return checks.length;
    }

    /** {@code true} for {@code AND}, {@code false} for {@code OR}. */
    public boolean requireAll() {
        return requireAll;
    }

    Set<String> references() {
        return references;
    }

    /**
     * Runs the checks for one player.
     *
     * <p>Short-circuits: under {@code AND} the first failing check decides, under {@code OR} the
     * first passing one does, so a condition that leads with a cheap permission check never pays
     * for the placeholder behind it.
     *
     * <p>A condition with no checks is {@code false}. That is the safe direction: an empty
     * condition is always a config mistake, and false means "this profile does not apply" rather
     * than "this profile applies to everybody".
     */
    public boolean matches(Player player, Conditions registry, PlaceholderResolver base, int depth) {
        if (checks.length == 0) return false;
        for (Check check : checks) {
            boolean passed = check.test(player, registry, base, depth);
            if (passed != requireAll) return passed;
        }
        return requireAll;
    }

    /** The {@code true}/{@code false} text for this player, with any nested references expanded. */
    String evaluate(Player player, Conditions registry, PlaceholderResolver base, int depth) {
        String output = matches(player, registry, base, depth) ? pass : fail;
        return registry.expandAt(player, output, base, depth);
    }
}
