package com.faboit.displaynames.text;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PlaceholderAPI-backed resolver, with the built-ins as a second pass.
 *
 * <p>Deliberately kept in its own class so the PlaceholderAPI types are only loaded when the
 * plugin is actually present on the server.
 *
 * <p>PlaceholderAPI leaves a placeholder untouched when no expansion is registered for its
 * identifier, so {@code %player_name%} stays raw on a server that never ran
 * {@code /papi ecloud download Player}. Running the built-ins over whatever comes back covers
 * the obvious player values in that case, and costs one scan that finds nothing when every
 * expansion is present.
 */
public final class PlaceholderApiResolver implements PlaceholderResolver {

    private final BuiltinPlaceholders fallback = new BuiltinPlaceholders();
    private final Logger logger;
    private volatile boolean warned;

    public PlaceholderApiResolver(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String resolve(Player player, String text) {
        String resolved;
        try {
            resolved = PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable failure) {
            // Throwable rather than Exception on purpose: a PlaceholderAPI version whose API has
            // moved throws NoSuchMethodError, and an expansion that misbehaves can throw anything
            // at all. Neither should take the nametag down - falling back to the raw text keeps
            // the tag rendering while the cause is logged once.
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "PlaceholderAPI threw while resolving placeholders. "
                        + "Falling back to the built-in ones; further failures are not logged.", failure);
            }
            resolved = text;
        }
        return fallback.resolve(player, resolved);
    }
}
