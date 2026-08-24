package com.faboit.displaynames.nametag;

import com.faboit.displaynames.DisplayNames;
import com.faboit.displaynames.config.Profile;
import com.faboit.displaynames.config.Settings;
import com.faboit.displaynames.text.NametagTemplate;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * One player's nametag: the entity, its upkeep task and the cached state that keeps the hot path
 * from doing any work it does not have to.
 *
 * <p>Every field here is confined to the owning player's region thread. The upkeep task runs on
 * that thread by construction, and the two entry points callable from elsewhere
 * ({@link #requestRefresh()} and {@link #requestRespawn()}) hop onto it first, so no field needs
 * to be volatile or guarded.
 */
public final class NametagHandle {

    private static final byte MARKER_VALUE = (byte) 1;

    private final DisplayNames plugin;
    private final NametagService service;
    private final Player player;

    private TextDisplay display;
    private UUID displayWorld;
    private ScheduledTask task;

    /** Profile and resolved string behind the text currently on the entity. */
    private Profile lastProfile;
    private String lastResolved;
    private boolean blank = true;

    /** Set when cached state must be ignored on the next pass (spawn, reload, resume). */
    private boolean forced = true;

    /** The grid this handle is currently counted in; a reload swaps the instance. */
    private ViewerIndex indexedIn;
    private UUID indexedWorld;
    private long indexedCell;

    private boolean stopped;

    NametagHandle(DisplayNames plugin, NametagService service, Player player) {
        this.plugin = plugin;
        this.service = service;
        this.player = player;
    }

    public Player player() {
        return player;
    }

    /** Starts the upkeep task on the player's region thread. */
    void start(Settings settings) {
        long period = settings.taskPeriod();
        long delay = 1L;
        if (settings.staggerUpdates() && period > 1L) {
            // Spread players across the interval so a refresh never lands on one busy tick.
            delay += Math.floorMod(player.getUniqueId().hashCode(), period);
        }
        task = player.getScheduler().runAtFixedRate(plugin, ignored -> runTick(), this::onRetired, delay, period);
        if (task == null) onRetired(); // player left between join and scheduling
    }

    /** Cancels upkeep and removes the entity. Must run on the player's region thread. */
    void stop() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        discardDisplay();
        unindex();
    }

    /** Best-effort teardown used during plugin shutdown, where schedulers may no longer run. */
    void stopImmediately() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        TextDisplay current = display;
        display = null;
        displayWorld = null;
        unindex();
        if (current == null) return;
        try {
            current.remove();
        } catch (RuntimeException ex) {
            // Wrong region thread during shutdown. The entity is non-persistent and carries our
            // marker, so it disappears with the restart or is swept on the next entity load.
            plugin.getLogger().log(Level.FINE, "Deferred nametag cleanup for " + player.getName(), ex);
        }
    }

    private void onRetired() {
        stopped = true;
        task = null;
        unindex();
    }

    /** Re-evaluates the tag as soon as the player's region thread is free. */
    public void requestRefresh() {
        player.getScheduler().execute(plugin, () -> {
            forced = true;
            runTick();
        }, null, 1L);
    }

    /** Drops and rebuilds the entity - used after a reload changes the entity's appearance. */
    public void requestRespawn() {
        player.getScheduler().execute(plugin, () -> {
            discardDisplay();
            forced = true;
            runTick();
        }, null, 1L);
    }

    // ---------------------------------------------------------------- hot path

    private void runTick() {
        if (stopped || !player.isOnline()) return;
        Settings settings = plugin.settings();

        // Done first and unconditionally: a player without a nametag of their own is still
        // somebody who can see other people's.
        updateIndex(settings);

        if (!service.isEnabledFor(player, settings)) {
            discardDisplay();
            return;
        }
        if (!ensureDisplay(settings)) return;

        if (shouldHide(settings)) {
            setBlank();
            return;
        }

        if (settings.skipWithoutViewers() && !hasViewer(settings)) {
            service.countSkipped();
            forced = true; // whatever changed while nobody watched is applied when someone does
            return;
        }

        render(settings);
    }

    private void render(Settings settings) {
        boolean force = forced;
        forced = false;

        Profile profile = settings.profileFor(player);
        NametagTemplate template = profile.template();

        if (template.isBlank()) {
            setBlank();
            return;
        }

        if (!template.dynamic()) {
            if (force || blank || lastProfile != profile) {
                apply(template.fixed(), profile, template.raw());
            }
            return;
        }

        // Nothing re-evaluates on its own when auto-refresh is off.
        if (!settings.autoRefresh() && !force && !blank && lastProfile == profile) return;

        String resolved = service.resolver().resolve(player, template.raw());
        if (!force && !blank && lastProfile == profile && resolved.equals(lastResolved)) {
            service.countSkipped();
            return;
        }
        apply(service.renderer().render(resolved), profile, resolved);
    }

    private void apply(Component text, Profile profile, String resolved) {
        display.text(text);
        lastProfile = profile;
        lastResolved = resolved;
        blank = false;
        service.countUpdate();
    }

    private void setBlank() {
        if (blank) return;
        display.text(Component.empty());
        blank = true;
        lastResolved = null;
        lastProfile = null;
        service.countUpdate();
    }

    // ---------------------------------------------------------------- entity lifecycle

    /**
     * @return {@code true} when {@link #display} is alive and mounted on the player
     */
    private boolean ensureDisplay(Settings settings) {
        TextDisplay current = display;
        if (current != null) {
            // Both checks read only the player's own entity data, which is always safe on this
            // thread; a cross-world teleport or a dismount shows up as a missing passenger.
            if (player.getWorld().getUID().equals(displayWorld) && player.getPassengers().contains(current)) {
                return true;
            }
            discardDisplay();
        }
        return spawn(settings);
    }

    private boolean spawn(Settings settings) {
        World world = player.getWorld();
        Consumer<TextDisplay> initialiser = entity -> {
            settings.display().apply(entity);
            entity.text(Component.empty());
            entity.getPersistentDataContainer().set(service.markerKey(), PersistentDataType.BYTE, MARKER_VALUE);
        };

        TextDisplay spawned;
        try {
            // Configured through the consumer so the entity is never visible in a default state.
            spawned = world.spawn(player.getLocation(), TextDisplay.class, initialiser);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not spawn a nametag for " + player.getName(), ex);
            return false;
        }

        if (!player.addPassenger(spawned)) {
            spawned.remove();
            return false; // retried on the next pass
        }
        if (settings.hideFromSelf()) player.hideEntity(plugin, spawned);

        display = spawned;
        displayWorld = world.getUID();
        blank = true;
        lastResolved = null;
        lastProfile = null;
        forced = true;
        return true;
    }

    private void discardDisplay() {
        TextDisplay current = display;
        display = null;
        displayWorld = null;
        blank = true;
        lastResolved = null;
        lastProfile = null;
        forced = true;
        if (current == null) return;

        // The entity may have been left behind in another world or region after a teleport, so
        // the removal is handed to whichever thread currently owns it.
        current.getScheduler().execute(plugin, current::remove, null, 1L);
    }

    // ---------------------------------------------------------------- visibility

    private boolean shouldHide(Settings settings) {
        if (settings.hideWhileSneaking() && player.isSneaking()) return true;
        if (settings.hideInSpectator() && player.getGameMode() == GameMode.SPECTATOR) return true;
        if (settings.hideWhileInvisible() && player.hasPotionEffect(PotionEffectType.INVISIBILITY)) return true;
        return settings.hideWhileVanished() && isVanished();
    }

    private boolean isVanished() {
        if (!player.hasMetadata("vanished")) return false;
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- viewer grid

    private void updateIndex(Settings settings) {
        ViewerIndex index = service.viewerIndex();
        if (!settings.skipWithoutViewers()) {
            unindex();
            return;
        }
        // A reload replaces the grid; drop the old registration rather than decrementing a cell
        // in the new grid that now belongs to somebody else.
        if (indexedIn != index) unindex();

        UUID world = player.getWorld().getUID();
        long cell = index.cellAt(player.getX(), player.getZ());
        if (indexedIn == null) {
            index.add(world, cell);
            indexedIn = index;
        } else {
            index.move(indexedWorld, indexedCell, world, cell);
        }
        indexedWorld = world;
        indexedCell = cell;
    }

    private void unindex() {
        if (indexedIn == null) return;
        indexedIn.remove(indexedWorld, indexedCell);
        indexedIn = null;
        indexedWorld = null;
    }

    private boolean hasViewer(Settings settings) {
        if (indexedIn == null) return true; // fail open rather than hide a tag somebody can see
        // The owner is counted in the grid too, so they only count as a viewer of their own tag
        // when they are actually shown it.
        return indexedIn.hasAtLeast(indexedWorld, indexedCell, settings.hideFromSelf() ? 2 : 1);
    }
}
