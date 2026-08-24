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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Owns every {@link NametagHandle} and the shared state they read. */
public final class NametagService {

    /** Permission that opts a player out of having a nametag entirely. */
    public static final String HIDDEN_PERMISSION = "displaynames.hidden";

    private final DisplayNames plugin;
    private final NamespacedKey markerKey;
    private final TeamGuard teamGuard;

    private final Map<UUID, NametagHandle> handles = new ConcurrentHashMap<>();
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();

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
        teamGuard.setEnabled(settings.hideVanillaNametag());
        for (Player online : Bukkit.getOnlinePlayers()) {
            add(online, settings);
        }
    }

    public void add(Player player, Settings settings) {
        NametagHandle handle = new NametagHandle(plugin, this, player);
        NametagHandle previous = handles.put(player.getUniqueId(), handle);
        if (previous != null) previous.stop();
        handle.start(settings);
        if (settings.hideVanillaNametag()) teamGuard.track(player);
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

        teamGuard.setEnabled(settings.hideVanillaNametag());

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

    public boolean isEnabledFor(Player player, Settings settings) {
        if (optedOut.contains(player.getUniqueId())) return false;
        if (settings.worldDisabled(player.getWorld().getName())) return false;
        return !player.hasPermission(HIDDEN_PERMISSION);
    }

    /** @return {@code true} when the player now has a nametag */
    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean nowVisible = optedOut.remove(id);
        if (!nowVisible) optedOut.add(id);
        NametagHandle handle = handles.get(id);
        if (handle != null) handle.requestRefresh();
        return nowVisible;
    }

    public boolean isOptedOut(Player player) {
        return optedOut.contains(player.getUniqueId());
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
