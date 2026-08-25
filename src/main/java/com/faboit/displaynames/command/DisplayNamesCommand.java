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
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /displaynames} - reload, force refreshes, diagnostics and cleanup. */
public final class DisplayNamesCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "<gray>[<gradient:#55ffff:#ffffff>DisplayNames</gradient><gray>] ";
    private static final List<String> SUB_COMMANDS = List.of("reload", "refresh", "status", "cleanup", "debug");

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
            case "status" -> status(sender);
            case "cleanup" -> cleanup(sender);
            case "debug" -> debug(sender);
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

    /**
     * Dumps what the plugin actually sees for the sender, so a "placeholders do not work" or
     * "the vanilla plate is still there" report can be settled with facts rather than a guess.
     */
    private void debug(CommandSender sender) {
        if (denied(sender, "displaynames.command.debug")) return;
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Run this in game - it reports what one player sees.");
            return;
        }

        Settings settings = plugin.settings();
        Profile profile = settings.profileFor(player);
        String raw = profile.template().raw();
        String resolved = plugin.service().resolver().resolve(player, raw);

        send(sender, "<gradient:#55ffff:#ffffff><bold>DisplayNames debug</bold></gradient>");
        line(sender, "Profile", profile.id());
        line(sender, "Resolver", plugin.service().resolver().getClass().getSimpleName());

        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        line(sender, "PlaceholderAPI", papi == null
                ? "<red>not found"
                : papi.getDescription().getVersion() + (papi.isEnabled() ? " <green>(enabled)" : " <red>(DISABLED)"));

        line(sender, "Template", escaped(raw));
        line(sender, "Resolved", escaped(resolved));
        if (raw.equals(resolved)) {
            line(sender, "Verdict", "<red>nothing was substituted <gray>- every placeholder above "
                    + "is unknown to PlaceholderAPI, so the expansion providing it is not installed. "
                    + "Try <white>/papi ecloud download Player<gray>, then <white>/papi reload<gray>.");
        } else {
            line(sender, "Verdict", "<green>substitution is working <gray>- anything still shown as "
                    + "%name% below is an expansion you have not installed.");
        }

        // The vanilla plate depends on the team on the board this player is VIEWING, not the main
        // one, so report exactly that.
        try {
            Scoreboard board = player.getScoreboard();
            boolean main = board.equals(Bukkit.getScoreboardManager().getMainScoreboard());
            Team team = board.getEntryTeam(player.getName());
            line(sender, "Your scoreboard", main ? "the main one" : "<yellow>a plugin's own board");
            line(sender, "Your team", team == null
                    ? "<yellow>none <gray>(nothing can hide your plate)"
                    : team.getName() + " -> nametag visibility "
                            + (team.getOption(Team.Option.NAME_TAG_VISIBILITY) == Team.OptionStatus.NEVER
                                    ? "<green>NEVER <gray>(plate hidden)"
                                    : "<red>" + team.getOption(Team.Option.NAME_TAG_VISIBILITY)));
        } catch (RuntimeException ex) {
            line(sender, "Scoreboard", "<red>could not be read: " + ex.getClass().getSimpleName());
        }
    }

    /** Neutralises MiniMessage tags so a template is shown literally rather than rendered. */
    private String escaped(String text) {
        return "<white>" + miniMessage.escapeTags(text);
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
            line(sender, "Your profile", settings.profileFor(player).id());
        }
    }

    private void usage(CommandSender sender, String label) {
        send(sender, "<gray>/" + label + " <white>reload <dark_gray>- reload config.yml");
        send(sender, "<gray>/" + label + " <white>refresh [player|*] <dark_gray>- force a re-render");
        send(sender, "<gray>/" + label + " <white>status <dark_gray>- runtime counters");
        send(sender, "<gray>/" + label + " <white>cleanup <dark_gray>- remove stray nametags");
        send(sender, "<gray>/" + label + " <white>debug <dark_gray>- why placeholders/plates misbehave");
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
            if (sub.equals("refresh")) {
                List<String> names = new ArrayList<>();
                names.add("*");
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
