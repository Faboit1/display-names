package com.faboit.displaynames;

import com.faboit.displaynames.command.DisplayNamesCommand;
import com.faboit.displaynames.config.Settings;
import com.faboit.displaynames.listener.PlayerListener;
import com.faboit.displaynames.nametag.NametagService;
import com.faboit.displaynames.text.PlaceholderApiResolver;
import com.faboit.displaynames.text.PlaceholderResolver;
import com.faboit.displaynames.text.TextRenderer;
import org.bukkit.command.PluginCommand;
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
        service.reload(renderer, fresh);
    }

    private void hookPlaceholderApi() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().warning("PlaceholderAPI was not found - %placeholders% will be left as-is. "
                    + "MiniMessage formatting still works.");
            service.resolver(PlaceholderResolver.NONE);
            placeholders = false;
            return;
        }
        service.resolver(new PlaceholderApiResolver());
        placeholders = true;
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
