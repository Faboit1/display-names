package com.faboit.displaynames.nametag;

import com.faboit.displaynames.DisplayNames;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.logging.Level;

/**
 * Removes the vanilla username plate above players.
 *
 * <p>The only way to do this without packet manipulation is a scoreboard team with
 * {@code NAME_TAG_VISIBILITY = NEVER}. Scoreboards live on the global region, so every touch is
 * dispatched to the global region scheduler - which is what makes this safe under Folia.
 */
public final class TeamGuard {

    private static final String TEAM_NAME = "displaynames";

    private final DisplayNames plugin;

    /** Confined to the global region thread. */
    private Team team;
    private boolean active;

    public TeamGuard(DisplayNames plugin) {
        this.plugin = plugin;
    }

    /** Applies the configured state; safe to call repeatedly, including on reload. */
    public void setEnabled(boolean enabled) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (enabled) {
                install();
            } else {
                uninstall();
            }
        });
    }

    public void track(Player player) {
        String entry = player.getName();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (active && team != null) team.addEntry(entry);
        });
    }

    public void untrack(Player player) {
        String entry = player.getName();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (active && team != null) team.removeEntry(entry);
        });
    }

    /** Called from {@code onDisable}, where the global region thread is already current. */
    public void shutdown() {
        uninstall();
    }

    private void install() {
        if (active) return;
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team existing = board.getTeam(TEAM_NAME);
            if (existing == null) existing = board.registerNewTeam(TEAM_NAME);
            existing.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            team = existing;
            active = true;
            for (Player online : Bukkit.getOnlinePlayers()) {
                team.addEntry(online.getName());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not hide vanilla nametags; another plugin may own the "
                    + "main scoreboard. Set visibility.hide-vanilla-nametag to false to silence this.", ex);
            team = null;
            active = false;
        }
    }

    private void uninstall() {
        active = false;
        Team current = team;
        team = null;
        if (current == null) return;
        try {
            current.unregister();
        } catch (RuntimeException ignored) {
            // Already gone - nothing to restore.
        }
    }
}
