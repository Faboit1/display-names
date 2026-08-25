package com.faboit.displaynames.listener;

import com.faboit.displaynames.DisplayNames;
import com.faboit.displaynames.nametag.NametagHandle;
import com.faboit.displaynames.nametag.NametagService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Keeps nametags in step with the events that vanilla uses to detach passengers, plus the
 * handful of state changes that should show up faster than the refresh interval.
 */
public final class PlayerListener implements Listener {

    private final DisplayNames plugin;

    public PlayerListener(DisplayNames plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        plugin.service().add(event.getPlayer(), plugin.settings());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().remove(event.getPlayer());
    }

    /**
     * Death ejects passengers, leaving the tag floating at the death site. Removing it here -
     * on the player's own region thread, where a direct remove is legal - is what stops it
     * hanging around; PlayerRespawnEvent then builds a fresh one.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        NametagHandle handle = plugin.service().handle(event.getEntity());
        if (handle != null) handle.dropDisplay();
    }

    /** Death ejects passengers, so the entity has to be rebuilt after respawning. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        respawnTag(event.getPlayer());
    }

    /**
     * Picks up PlaceholderAPI if it enables after us - installed at runtime, or loaded later
     * despite the softdepend. Without this the tags show raw %placeholder% text until a restart.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"PlaceholderAPI".equals(event.getPlugin().getName())) return;
        plugin.hookPlaceholderApi();
    }

    /** The old entity stays behind in the old world; build a new one in the new one. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        respawnTag(event.getPlayer());
    }

    /** Teleporting dismounts passengers; refresh re-mounts without waiting for the timer. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        refreshTag(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!plugin.settings().hideWhileSneaking()) return;
        refreshTag(event.getPlayer());
    }

    /**
     * Sweeps nametag entities left behind by a crash. Tags are non-persistent, so anything that
     * still carries our marker when a chunk's entities load is by definition an orphan.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        NametagService service = plugin.service();
        for (Entity entity : event.getEntities()) {
            service.sweepOrphan(entity);
        }
    }

    private void refreshTag(Player player) {
        NametagHandle handle = plugin.service().handle(player);
        if (handle != null) handle.requestRefresh();
    }

    private void respawnTag(Player player) {
        NametagHandle handle = plugin.service().handle(player);
        if (handle != null) handle.requestRespawn();
    }
}
