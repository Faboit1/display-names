package com.faboit.displaynames;

import com.faboit.displaynames.command.DisplayNamesCommand;
import com.faboit.displaynames.config.Profile;
import com.faboit.displaynames.config.Settings;
import com.faboit.displaynames.listener.PlayerListener;
import com.faboit.displaynames.nametag.NametagService;
import com.faboit.displaynames.text.BuiltinPlaceholders;
import com.faboit.displaynames.text.PlaceholderApiResolver;
import com.faboit.displaynames.text.PlaceholderResolver;
import com.faboit.displaynames.text.TextRenderer;
import org.bukkit.command.PluginCommand;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Replaces the vanilla username plate with a multi-line {@code TextDisplay} mounted on the player.
 *
 * <p>Design notes, since they drive most of the code:
 *
 * <ul>
 *   <li><b>One entity per player, mounted.</b> The client interpolates a passenger's position, so
 *       the server never teleports the tag and never sends a movement packet for it. Multiple
 *       lines are newlines inside a single component, not several entities.</li>
 *   <li><b>Region-local by construction.</b> Each player's upkeep runs on that player's
 *       {@code EntityScheduler}, which under Folia <em>is</em> the thread that owns the player,
 *       the tag and the placeholders. Work spreads across region threads for free, and there is
 *       no shared mutable per-player state to guard.</li>
 *   <li><b>Do nothing whenever possible.</b> Static formats are parsed once at load; dynamic ones
 *       only re-send text when the resolved string actually changed; and placeholders are not
 *       evaluated at all for players nobody is close enough to see.</li>
 * </ul>
 */
public final class DisplayNames extends JavaPlugin {

    private volatile Settings settings;
    private NametagService service;
    private boolean placeholders;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        TextRenderer renderer = Settings.createRenderer(getConfig(), getLogger());
        this.settings = Settings.load(getConfig(), renderer, getLogger());
        this.service = new NametagService(this, renderer, settings);

        hookPlaceholderApi();
        registerProfilePermissions(settings);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        PluginCommand command = getCommand("displaynames");
        if (command != null) {
            DisplayNamesCommand handler = new DisplayNamesCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        service.start(settings);

        getLogger().info("Enabled - refresh every " + (settings.autoRefresh()
                ? settings.refreshInterval() + " ticks" : "manual only")
                + ", " + settings.profiles().size() + " profile(s), placeholders: "
                + (placeholders ? "PlaceholderAPI" : "none"));
    }

    @Override
    public void onDisable() {
        if (service != null) service.shutdown();
    }

    /** Re-reads config.yml and rebuilds every nametag with the new settings. */
    public void reload() {
        reloadConfig();
        TextRenderer renderer = Settings.createRenderer(getConfig(), getLogger());
        Settings fresh = Settings.load(getConfig(), renderer, getLogger());
        // Published before the rebuild so refresh tasks never read the old snapshot afterwards.
        this.settings = fresh;
        registerProfilePermissions(fresh);
        service.reload(renderer, fresh);
    }

    /**
     * Gives every profile permission an explicit default of {@code false}.
     *
     * <p>Bukkit resolves a permission nobody has declared as {@link PermissionDefault#OP}, so an
     * undeclared profile node is held by every operator. That silently hands admins the
     * highest-priority profile and overrides the nametag they actually configured - a trap that
     * applies to profiles the user invents just as much as to the shipped examples, which is why
     * this registers them at runtime rather than listing a couple of nodes in plugin.yml.
     *
     * <p>An operator who genuinely wants a profile can still grant the node in their permission
     * plugin; this only stops it being granted by accident.
     */
    private void registerProfilePermissions(Settings source) {
        PluginManager pluginManager = getServer().getPluginManager();
        for (Profile profile : source.profiles()) {
            String node = profile.permission();
            if (node == null || node.isBlank()) continue;
            if (pluginManager.getPermission(node) != null) continue;
            pluginManager.addPermission(new Permission(node,
                    "Grants the DisplayNames '" + profile.id() + "' nametag profile.",
                    PermissionDefault.FALSE));
        }
    }

    /**
     * Points the renderer at PlaceholderAPI if it is present, or at the built-in fallbacks.
     *
     * <p>Public and idempotent because it is called again from {@code PluginEnableEvent}: a
     * server that installs PlaceholderAPI later, or loads it after us despite the softdepend,
     * would otherwise show raw {@code %placeholder%} text until the next restart.
     */
    public void hookPlaceholderApi() {
        boolean present = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (present == placeholders && service.resolver() != PlaceholderResolver.NONE) return;

        if (!present) {
            getLogger().warning("""
                    PlaceholderAPI is NOT installed.

                    Only a few built-in %player_...% placeholders will resolve; everything else,
                    including %luckperms_prefix% and %vault_eco_balance%, is shown as raw text.
                    Install PlaceholderAPI and its expansions to fix this - no restart needed,
                    DisplayNames picks it up as soon as it enables.""");
            service.resolver(new BuiltinPlaceholders());
            placeholders = false;
            return;
        }
        service.resolver(new PlaceholderApiResolver(getLogger()));
        placeholders = true;
        getLogger().info("Hooked into PlaceholderAPI.");
        service.refreshAll();
    }

    /** The current configuration snapshot; never null after {@code onEnable}. */
    public Settings settings() {
        return settings;
    }

    public NametagService service() {
        return service;
    }

    public boolean placeholdersAvailable() {
        return placeholders;
    }
}
