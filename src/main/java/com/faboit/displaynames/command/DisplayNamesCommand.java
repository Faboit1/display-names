package com.faboit.displaynames.command;

import com.faboit.displaynames.DisplayNames;
import com.faboit.displaynames.config.Profile;
import com.faboit.displaynames.config.Settings;
import com.faboit.displaynames.nametag.NametagHandle;
import com.faboit.displaynames.nametag.NametagService;
import com.faboit.displaynames.nametag.TeamGuard;
import com.faboit.displaynames.text.TextRenderer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /displaynames} - reload, force refreshes, toggle a player's tag and read counters. */
public final class DisplayNamesCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "<gray>[<gradient:#55ffff:#ffffff>DisplayNames</gradient><gray>] ";
    private static final List<String> SUB_COMMANDS = List.of("reload", "refresh", "toggle", "status", "cleanup");

    private final DisplayNames plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public DisplayNamesCommand(DisplayNames plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "refresh" -> refresh(sender, args);
            case "toggle" -> toggle(sender, args);
            case "status" -> status(sender);
            case "cleanup" -> cleanup(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        if (denied(sender, "displaynames.command.reload")) return;
        try {
            long start = System.nanoTime();
            plugin.reload();
            long millis = (System.nanoTime() - start) / 1_000_000L;
            send(sender, "<green>Configuration reloaded in " + millis + "ms; nametags are being rebuilt.");
        } catch (RuntimeException ex) {
            send(sender, "<red>Reload failed: " + ex.getMessage() + " <gray>(see console)");
            plugin.getLogger().warning("Reload failed: " + ex);
        }
    }

    private void refresh(CommandSender sender, String[] args) {
        if (denied(sender, "displaynames.command.refresh")) return;
        NametagService service = plugin.service();

        if (args.length < 2 || args[1].equals("*")) {
            service.refreshAll();
            send(sender, "<green>Refreshing <white>" + service.tracked() + "<green> nametag(s).");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "<red>No online player named <white>" + args[1] + "<red>.");
            return;
        }
        NametagHandle handle = service.handle(target);
        if (handle == null) {
            send(sender, "<red>That player is not tracked yet.");
            return;
        }
        handle.requestRefresh();
        send(sender, "<green>Refreshed <white>" + target.getName() + "<green>'s nametag.");
    }

    private void toggle(CommandSender sender, String[] args) {
        if (denied(sender, "displaynames.command.toggle")) return;

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                send(sender, "<red>No online player named <white>" + args[1] + "<red>.");
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            send(sender, "<red>Usage from console: <white>/dn toggle <player>");
            return;
        }

        boolean visible = plugin.service().toggle(target);
        send(sender, visible
                ? "<green>Nametag enabled for <white>" + target.getName() + "<green>."
                : "<yellow>Nametag hidden for <white>" + target.getName() + "<yellow>.");
    }

    private void cleanup(CommandSender sender) {
        if (denied(sender, "displaynames.command.cleanup")) return;
        send(sender, "<gray>Sweeping for stray nametags near online players...");
        plugin.service().sweepNearbyOrphans(64.0D, removed -> send(sender, removed == 0
                ? "<green>No stray nametags found."
                : "<green>Removed <white>" + removed + "<green> stray nametag(s). "
                        + "<gray>Any further from a player need a restart - they are "
                        + "non-persistent, so a restart always clears them."));
    }

    private void status(CommandSender sender) {
        if (denied(sender, "displaynames.command.status")) return;

        Settings settings = plugin.settings();
        TextRenderer renderer = plugin.service().renderer();
        NametagService service = plugin.service();

        long updates = service.updateCount();
        long skipped = service.skippedCount();
        long total = updates + skipped;
        String skipRate = total == 0 ? "n/a" : (skipped * 100L / total) + "%";

        send(sender, "<gradient:#55ffff:#ffffff><bold>DisplayNames status</bold></gradient>");
        line(sender, "Tracked players", String.valueOf(service.tracked()));
        line(sender, "Refresh interval", settings.autoRefresh() ? settings.refreshInterval() + " ticks" : "manual");
        line(sender, "Placeholders", plugin.placeholdersAvailable() ? "PlaceholderAPI" : "unavailable");
        line(sender, "Profiles", String.valueOf(settings.profiles().size()));
        line(sender, "Text updates sent", String.valueOf(updates));
        line(sender, "Updates avoided", skipped + " <dark_gray>(" + skipRate + " of passes)");
        line(sender, "MiniMessage parses", String.valueOf(renderer.parseCount()));
        line(sender, "Component cache", renderer.cacheSize() + " entries, " + renderer.cacheHitCount() + " hits");
        line(sender, "Viewer grid", settings.skipWithoutViewers()
                ? service.viewerIndex().cellSize() + " blocks"
                : "disabled");

        // The usual reason vanilla plates are still showing is that a sidebar or tab plugin moved
        // players onto its own scoreboard, so surface how many boards the sweep actually reached.
        TeamGuard guard = service.teamGuard();
        if (guard.mode() == TeamGuard.Mode.NONE) {
            line(sender, "Vanilla nametags", "left visible <dark_gray>(mode: NONE)");
        } else {
            line(sender, "Vanilla nametags", "hidden via " + guard.mode()
                    + " <dark_gray>(" + guard.sweeps() + " sweeps)");
            line(sender, "  scoreboards swept", String.valueOf(guard.lastScoreboards()));
            line(sender, "  players covered", String.valueOf(guard.lastCovered()));
            if (guard.mode() == TeamGuard.Mode.ADOPT) {
                line(sender, "  teams adopted", String.valueOf(guard.lastAdopted()));
            }
            if (guard.lastError() != null) {
                line(sender, "  last error", "<red>" + guard.lastError());
            }
        }

        if (sender instanceof Player player) {
            Profile profile = settings.profileFor(player);
            line(sender, "Your profile", profile.id() + (service.isOptedOut(player) ? " <gray>(hidden)" : ""));
        }
    }

    private void usage(CommandSender sender, String label) {
        send(sender, "<gray>/" + label + " <white>reload <dark_gray>- reload config.yml");
        send(sender, "<gray>/" + label + " <white>refresh [player|*] <dark_gray>- force a re-render");
        send(sender, "<gray>/" + label + " <white>toggle [player] <dark_gray>- hide or show a nametag");
        send(sender, "<gray>/" + label + " <white>status <dark_gray>- runtime counters");
        send(sender, "<gray>/" + label + " <white>cleanup <dark_gray>- remove stray nametags");
    }

    private boolean denied(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return false;
        send(sender, "<red>You do not have permission to do that.");
        return true;
    }

    private void line(CommandSender sender, String key, String value) {
        sender.sendMessage(miniMessage.deserialize("<gray>" + key + ": <white>" + value));
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(miniMessage.deserialize(PREFIX + "<reset>" + message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("refresh") || sub.equals("toggle")) {
                List<String> names = new ArrayList<>();
                if (sub.equals("refresh")) names.add("*");
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(option);
        }
        return matches;
    }
}
