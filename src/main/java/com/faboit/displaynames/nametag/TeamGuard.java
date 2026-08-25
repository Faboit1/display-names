package com.faboit.displaynames.nametag;

import com.faboit.displaynames.DisplayNames;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Removes the vanilla username plate above players.
 *
 * <p>Without packet manipulation the only lever is a scoreboard team with
 * {@code NAME_TAG_VISIBILITY = NEVER}. The subtlety that makes a naive implementation silently do
 * nothing: a team only affects the plates a player sees if it lives on <em>the scoreboard that
 * player is currently viewing</em>. Any sidebar, tab or scoreboard plugin calls
 * {@link Player#setScoreboard} and moves players off the main scoreboard, at which point a team
 * registered only there is invisible to them and every vanilla nametag comes back.
 *
 * <p>So this sweeps every scoreboard actually in use, not just the main one, and repeats on a
 * timer because those plugins swap and rebuild scoreboards continuously.
 *
 * <p>Scoreboards live on the global region, so every touch is dispatched to the global region
 * scheduler - which is what makes this safe under Folia.
 */
public final class TeamGuard {

    /** How the vanilla plate is suppressed. */
    public enum Mode {
        /** Do nothing; vanilla nametags stay. */
        NONE,
        /**
         * Put every player into our own team. Simple and self-contained, but a player can only be
         * in one team, so this fights any plugin that uses teams for tab-list sorting.
         */
        TEAM,
        /**
         * Leave existing team membership alone and set {@code NAME_TAG_VISIBILITY = NEVER} on
         * whatever team a player is already in, falling back to our own team for players who are
         * in none. Compatible with tab-list sorting, at the cost of editing another plugin's team.
         */
        ADOPT;

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null) return fallback;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    private final DisplayNames plugin;
    private final AtomicBoolean sweepQueued = new AtomicBoolean();

    /** Previous visibility of teams we adopted, so shutdown can put them back. */
    private final Map<String, Team.OptionStatus> adoptedBefore = new HashMap<>();

    private volatile Mode mode = Mode.NONE;
    private volatile String teamName = "displaynames";
    private ScheduledTask task;

    // Diagnostics, published for /dn status.
    private volatile int lastScoreboards;
    private volatile int lastCovered;
    private volatile int lastAdopted;
    private volatile String lastError;
    private volatile long sweeps;

    public TeamGuard(DisplayNames plugin) {
        this.plugin = plugin;
    }

    /** Applies the configured state. Safe to call repeatedly, including on reload. */
    public void configure(Mode newMode, String newTeamName, long reassertInterval) {
        String sanitised = sanitiseTeamName(newTeamName);
        Mode previous = this.mode;

        if (previous != Mode.NONE && (previous != newMode || !sanitised.equals(this.teamName))) {
            // The shape of what we installed is changing; undo the old one first.
            Bukkit.getGlobalRegionScheduler().execute(plugin, this::restore);
        }

        this.teamName = sanitised;
        this.mode = newMode;

        if (task != null) {
            task.cancel();
            task = null;
        }
        if (newMode == Mode.NONE) return;

        requestSweep();
        if (reassertInterval > 0) {
            task = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignored -> sweep(), reassertInterval, reassertInterval);
        }
    }

    /**
     * Queues a sweep, collapsing bursts (a wave of joins) into a single pass.
     */
    public void requestSweep() {
        if (mode == Mode.NONE) return;
        if (!sweepQueued.compareAndSet(false, true)) return;
        Bukkit.getGlobalRegionScheduler().execute(plugin, this::sweep);
    }

    /**
     * Sweeps again after a delay.
     *
     * <p>Tab and sidebar plugins usually call {@code setScoreboard} a few ticks after a player
     * joins, not during the join event, so the sweep that runs immediately can land on the board
     * the player is about to be moved off.
     */
    public void requestDelayedSweep(long delayTicks) {
        if (mode == Mode.NONE) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> sweep(), Math.max(1L, delayTicks));
    }

    /** Drops a leaving player from our own team so the entry does not linger. */
    public void untrack(Player player) {
        if (mode == Mode.NONE) return;
        String entry = player.getName();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            for (Scoreboard board : boardsInUse()) {
                Team own = board.getTeam(teamName);
                if (own != null && own.hasEntry(entry)) own.removeEntry(entry);
            }
        });
    }

    /** Called from {@code onDisable}, where the global region thread is already current. */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        restore();
        mode = Mode.NONE;
    }

    // ---------------------------------------------------------------- the sweep

    private void sweep() {
        sweepQueued.set(false);
        Mode current = mode;
        if (current == Mode.NONE) return;

        try {
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            Set<Scoreboard> boards = boardsInUse();

            int covered = 0;
            int adopted = 0;
            for (Scoreboard board : boards) {
                Team own = null;
                for (Player player : online) {
                    String entry = player.getName();
                    Team existing = board.getEntryTeam(entry);

                    if (current == Mode.ADOPT && existing != null && !existing.getName().equals(teamName)) {
                        if (hide(board, existing)) adopted++;
                        covered++;
                        continue;
                    }

                    if (own == null) own = ensureOwnTeam(board);
                    if (own == null) continue;
                    if (!own.hasEntry(entry)) own.addEntry(entry);
                    covered++;
                }
            }

            lastScoreboards = boards.size();
            lastCovered = covered;
            lastAdopted = adopted;
            lastError = null;
            sweeps++;
        } catch (RuntimeException ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            plugin.getLogger().log(Level.WARNING, "Could not hide vanilla nametags. Set "
                    + "visibility.hide-vanilla-nametag.mode to NONE to stop trying.", ex);
        }
    }

    /** The main scoreboard plus every distinct one a player is actually looking at. */
    private Set<Scoreboard> boardsInUse() {
        Set<Scoreboard> boards = Collections.newSetFromMap(new IdentityHashMap<>());
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player player : Bukkit.getOnlinePlayers()) {
            boards.add(player.getScoreboard());
        }
        return boards;
    }

    private Team ensureOwnTeam(Scoreboard board) {
        Team own = board.getTeam(teamName);
        if (own == null) own = board.registerNewTeam(teamName);
        if (own.getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.NEVER) {
            own.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return own;
    }

    /** @return {@code true} if this call is what changed the team */
    private boolean hide(Scoreboard board, Team team) {
        Team.OptionStatus before = team.getOption(Team.Option.NAME_TAG_VISIBILITY);
        if (before == Team.OptionStatus.NEVER) return false;
        adoptedBefore.putIfAbsent(key(board, team.getName()), before);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        return true;
    }

    private void restore() {
        try {
            for (Scoreboard board : boardsInUse()) {
                Team own = board.getTeam(teamName);
                if (own != null) own.unregister();

                for (Team team : board.getTeams()) {
                    Team.OptionStatus before = adoptedBefore.remove(key(board, team.getName()));
                    if (before != null) team.setOption(Team.Option.NAME_TAG_VISIBILITY, before);
                }
            }
        } catch (RuntimeException ignored) {
            // Shutting down, or the scoreboard is already gone. Nothing left to restore.
        }
        adoptedBefore.clear();
    }

    private static String key(Scoreboard board, String team) {
        return System.identityHashCode(board) + "/" + team;
    }

    private String sanitiseTeamName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "displaynames";
        if (value.length() > 16) {
            plugin.getLogger().warning("visibility.hide-vanilla-nametag.team-name '" + value
                    + "' is longer than 16 characters and was truncated.");
            return value.substring(0, 16);
        }
        return value;
    }

    // ---------------------------------------------------------------- diagnostics

    public Mode mode() {
        return mode;
    }

    public String teamName() {
        return teamName;
    }

    public int lastScoreboards() {
        return lastScoreboards;
    }

    public int lastCovered() {
        return lastCovered;
    }

    public int lastAdopted() {
        return lastAdopted;
    }

    public long sweeps() {
        return sweeps;
    }

    /** The most recent failure, or {@code null} when the last sweep was clean. */
    public String lastError() {
        return lastError;
    }
}
