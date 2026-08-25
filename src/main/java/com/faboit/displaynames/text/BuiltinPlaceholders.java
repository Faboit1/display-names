package com.faboit.displaynames.text;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.function.Function;

/**
 * A small set of player placeholders resolved without PlaceholderAPI.
 *
 * <p>Used only when PlaceholderAPI is absent. Without it the nametag would show raw
 * {@code %player_name%} text, which looks exactly like the plugin being broken; covering the
 * obvious player values means a server with no expansions installed still gets something
 * sensible. Anything not listed here is left untouched for PlaceholderAPI to handle if it
 * shows up later.
 */
public final class BuiltinPlaceholders implements PlaceholderResolver {

    @Override
    public String resolve(Player player, String text) {
        return replace(text, token -> valueOf(player, token));
    }

    /**
     * Substitutes every {@code %token%} the lookup recognises, leaving the rest untouched.
     *
     * <p>Split out from {@link #resolve} so the index arithmetic can be tested without a server.
     *
     * @param lookup returns the replacement, or {@code null} for a token it does not handle
     */
    static String replace(String text, Function<String, String> lookup) {
        if (text == null || text.indexOf('%') < 0) return text;

        StringBuilder out = null;
        int copied = 0;
        int scan = 0;

        while (true) {
            int open = text.indexOf('%', scan);
            if (open < 0) break;
            int close = text.indexOf('%', open + 1);
            if (close < 0) break;

            String value = lookup.apply(text.substring(open + 1, close));
            if (value == null) {
                // Not one of ours. Resume at the closing marker, which may itself open the
                // next placeholder in a run like %a%%b%.
                scan = close;
                continue;
            }
            if (out == null) out = new StringBuilder(text.length() + 16);
            out.append(text, copied, open).append(value);
            copied = close + 1;
            scan = copied;
        }

        if (out == null) return text;
        return out.append(text, copied, text.length()).toString();
    }

    /** @return the replacement, or {@code null} when the token is not one we know */
    private static String valueOf(Player player, String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "player_name", "player" -> player.getName();
            case "player_displayname" ->
                    PlainTextComponentSerializer.plainText().serialize(player.displayName());
            case "player_uuid" -> player.getUniqueId().toString();
            case "player_world" -> player.getWorld().getName();
            case "player_health" -> Integer.toString((int) Math.ceil(player.getHealth()));
            case "player_max_health" -> Integer.toString((int) Math.ceil(player.getMaxHealth()));
            case "player_food_level" -> Integer.toString(player.getFoodLevel());
            case "player_level" -> Integer.toString(player.getLevel());
            case "player_gamemode" -> player.getGameMode().name();
            case "player_ping" -> Integer.toString(player.getPing());
            case "player_x" -> Integer.toString(player.getLocation().getBlockX());
            case "player_y" -> Integer.toString(player.getLocation().getBlockY());
            case "player_z" -> Integer.toString(player.getLocation().getBlockZ());
            case "server_online" -> Integer.toString(Bukkit.getOnlinePlayers().size());
            default -> null;
        };
    }
}
