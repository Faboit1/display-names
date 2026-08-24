package com.faboit.displaynames.text;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI-backed resolver.
 *
 * <p>Deliberately kept in its own class so the PlaceholderAPI types are only loaded when the
 * plugin is actually present on the server.
 */
public final class PlaceholderApiResolver implements PlaceholderResolver {

    @Override
    public String resolve(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
