package com.faboit.displaynames.text;

import org.bukkit.entity.Player;

/**
 * Resolves {@code %placeholder%} tokens for a player.
 *
 * <p>Implementations are called from the player's own region thread, which is where the Bukkit
 * API is legal to touch for that player on Folia.
 */
@FunctionalInterface
public interface PlaceholderResolver {

    /** No-op resolver used when PlaceholderAPI is not installed. */
    PlaceholderResolver NONE = (player, text) -> text;

    String resolve(Player player, String text);
}
