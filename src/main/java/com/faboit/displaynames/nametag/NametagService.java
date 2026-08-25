package com.faboit.displaynames.nametag;

import com.faboit.displaynames.DisplayNames;
import com.faboit.displaynames.config.Settings;
import com.faboit.displaynames.text.PlaceholderResolver;
import com.faboit.displaynames.text.TextRenderer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;

/** Owns every {@link NametagHandle} and the shared state they read. */
public final class NametagService {

    /** Permission that opts a player out of having a nametag entirely. */
    public static final String HIDDEN_PERMISSION = "displaynames.hidden";

    private final DisplayNames plugin;
    private final NamespacedKey markerKey;
    private final TeamGuard teamGuard;

    private final Map<UUID, NametagHandle> handles = new ConcurrentHashMap<>();

    private final LongAdder updates = new LongAdder();
    private final LongAdder skipped = new LongAdder();

    private volatile TextRenderer renderer;
    private volatile PlaceholderResolver resolver = PlaceholderResolver.NONE;
    private volatile ViewerIndex viewerIndex;

    public NametagService(DisplayNames plugin, TextRenderer renderer, Settings settings) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "nametag");
        this.teamGuard = new TeamGuard(plugin);
        this.renderer = renderer;
        this.viewerIndex = new ViewerIndex(settings.viewerGridSize());
    }

    // ---------------------------------------------------------------- lifecycle

    public void start(Settings settings) {
        teamGuard.configure(settings.teamMode(), settings.teamName(), settings.teamReassertInterval());
        for (Player online : Bukkit.getOnlinePlayers()) {
            add(online, settings);
        }
    }

    public void add(Player player, Settings settings) {
        NametagHandle handle = new NametagHandle(plugin, this, player);
        NametagHandle previous = handles.put(player.getUniqueId(), handle);
        if (previous != null) previous.stop();
        handle.start(settings);
        // A joining player has to be hidden on every scoreboard already in use, and every player
        // already online has to be hidden on whatever scoreboard this one ends up viewing. The
        // delayed passes catch tab plugins that swap the player's scoreboard shortly after join.
        teamGuard.requestSweep();
        teamGuard.requestDelayedSweep(20L);
        teamGuard.requestDelayedSweep(100L);
    }

    /** Called from {@code PlayerQuitEvent}, i.e. already on the player's region thread. */
    public void remove(Player player) {
        NametagHandle handle = handles.remove(player.getUniqueId());
        if (handle != null) handle.stop();
        teamGuard.untrack(player);
    }

    /** Rebuilds every nametag - appearance settings only take effect on a fresh entity. */
    public void reload(TextRenderer newRenderer, Settings settings) {
        TextRenderer previous = this.renderer;
        this.renderer = newRenderer;
        if (previous != null) previous.invalidate();

        ViewerIndex rebuilt = new ViewerIndex(settings.viewerGridSize());
        this.viewerIndex = rebuilt;

        teamGuard.configure(settings.teamMode(), settings.teamName(), settings.teamReassertInterval());

        for (Player online : Bukkit.getOnlinePlayers()) {
            NametagHandle handle = handles.get(online.getUniqueId());
            if (handle == null) {
                add(online, settings);
            } else {
                handle.requestRespawn();
            }
        }
    }

    public void shutdown() {
        for (NametagHandle handle : handles.values()) {
            handle.stopImmediately();
        }
        handles.clear();
        viewerIndex.clear();
        teamGuard.shutdown();
    }

    // ---------------------------------------------------------------- queries

    public NametagHandle handle(Player player) {
        return handles.get(player.getUniqueId());
    }

    public Collection<NametagHandle> handles() {
        return handles.values();
    }

    public void refreshAll() {
        for (NametagHandle handle : handles.values()) {
            handle.requestRefresh();
        }
    }

    /**
     * Every online player gets a nametag unless the server explicitly opted them out through
     * config or a permission. There is deliberately no runtime toggle: a per-player switch is
     * one more way for a tag to be silently missing, which is the opposite of what this is for.
     */
    public boolean isEnabledFor(Player player, Settings settings) {
        if (settings.worldDisabled(player.getWorld().getName())) return false;
        return !player.hasPermission(HIDDEN_PERMISSION);
    }

    /**
     * Removes stray nametag entities near online players.
     *
     * <p>A tag left behind by a death, a plugin reload or an unclean shutdown just floats there:
     * it is non-persistent, so a full restart clears it, but nothing else does. This is the
     * manual broom for a world that already has a pile of them.
     *
     * <p>Best effort by nature - a nearby-entity scan is only legal for the region that owns the
     * player, so anything far from every online player is out of reach and waits for a restart.
     *
     * @param whenDone receives the number removed, once every player's region has reported
     */
    public void sweepNearbyOrphans(double radius, IntConsumer whenDone) {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) {
            whenDone.accept(0);
            return;
        }
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger(online.size());
        Runnable settle = () -> {
            if (pending.decrementAndGet() == 0) whenDone.accept(removed.get());
        };

        for (Player player : online) {
            player.getScheduler().execute(plugin, () -> {
                try {
                    for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
                        if (!(nearby instanceof TextDisplay)) continue;
                        if (!nearby.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) continue;
                        // A live tag rides its owner; anything unmounted is an orphan.
                        if (nearby.getVehicle() != null) continue;
                        nearby.remove();
                        removed.incrementAndGet();
                    }
                } catch (RuntimeException outOfRegion) {
                    // Region boundaries make this best effort under Folia.
                } finally {
                    settle.run();
                }
            }, settle, 1L);
        }
    }

    /**
     * Removes a nametag entity orphaned by a crash or an unclean shutdown.
     *
     * @return {@code true} when the entity belonged to this plugin and was removed
     */
    public boolean sweepOrphan(Entity entity) {
        if (!(entity instanceof TextDisplay)) return false;
        if (!entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) return false;
        entity.remove();
        return true;
    }

    // ---------------------------------------------------------------- accessors

    public NamespacedKey markerKey() {
        return markerKey;
    }

    public TextRenderer renderer() {
        return renderer;
    }

    public PlaceholderResolver resolver() {
        return resolver;
    }

    public void resolver(PlaceholderResolver resolver) {
        this.resolver = resolver;
    }

    public ViewerIndex viewerIndex() {
        return viewerIndex;
    }

    public TeamGuard teamGuard() {
        return teamGuard;
    }

    void countUpdate() {
        updates.increment();
    }

    void countSkipped() {
        skipped.increment();
    }

    public long updateCount() {
        return updates.sum();
    }

    public long skippedCount() {
        return skipped.sum();
    }

    public int tracked() {
        return handles.size();
    }
}
