package dev.plex.plexvariables;

import dev.plex.plexvariables.command.PlexVariablesCommand;
import dev.plex.plexvariables.api.PlexVariablesApi;
import dev.plex.plexvariables.implementation.DefaultPlexVariablesApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import dev.plex.plexvariables.config.ConfigurationLoader;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.config.ReloadService;
import dev.plex.plexvariables.listener.StoragePlayerListener;
import dev.plex.plexvariables.placeholder.PlexVariablesExpansion;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.variable.VariableResolver;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PlexVariables extends JavaPlugin {
    private final AtomicReference<PluginState> state = new AtomicReference<>();
    private PlexVariablesExpansion expansion;
    private ReloadService reloadService;
    private StorageManager storageManager;

    @Override
    public void onEnable() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().severe("PlaceholderAPI is required. Install it before enabling PlexVariables.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        long started = System.nanoTime();
        try {
            installDefaults();
            var loader = new ConfigurationLoader(getDataFolder().toPath(), getLogger());
            state.set(loader.load());

            Path dbFile = getDataFolder().toPath().resolve("data.db");
            storageManager = new StorageManager(dbFile, () -> state.get().settings(), getLogger());
            storageManager.init();

            getServer().getPluginManager().registerEvents(new StoragePlayerListener(storageManager), this);
            for (Player player : getServer().getOnlinePlayers()) {
                storageManager.loadPlayerAsync(player.getUniqueId());
            }

            var resolver = new VariableResolver(state::get, PlaceholderAPI::setPlaceholders, storageManager, getLogger());
            reloadService = new ReloadService(this, loader, state, resolver::clearWarnings);
            var executor = new PlexVariablesCommand(state::get, resolver, reloadService, storageManager, getServer(), getLogger());
            var command = Objects.requireNonNull(getCommand("plexvariables"), "Missing plugin.yml command");
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            expansion = new PlexVariablesExpansion(resolver, getPluginMeta().getVersion(),
                    String.join(", ", getPluginMeta().getAuthors()));
            if (!expansion.register()) {
                expansion = null;
                throw new IllegalStateException("Could not register the 'plexvar' expansion; check for an identifier conflict.");
            }
            getServer().getServicesManager().register(PlexVariablesApi.class,
                    new DefaultPlexVariablesApi(state::get, resolver, storageManager, Bukkit::isPrimaryThread),
                    this, ServicePriority.Normal);
            PluginState loaded = state.get();
            getLogger().info("Loaded " + loaded.variables().size() + " variables from "
                    + loaded.filesLoaded() + " files in " + (System.nanoTime() - started) / 1_000_000 + "ms.");
            getLogger().info("Registered PlaceholderAPI expansion: %plexvar_<variable>%");
        } catch (IOException | RuntimeException exception) {
            getLogger().severe("Could not enable PlexVariables: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void installDefaults() throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        for (String resource : new String[]{"config.yml", "messages.yml"}) {
            if (!Files.exists(getDataFolder().toPath().resolve(resource))) {
                saveResource(resource, false);
            }
        }
        var variables = getDataFolder().toPath().resolve("variables");
        if (!Files.exists(variables)) {
            Files.createDirectories(variables);
            saveResource("variables/general.yml", false);
            saveResource("variables/examples.yml", false);
            saveResource("variables/conditions.yml", false);
            saveResource("variables/expressions.yml", false);
            saveResource("variables/storage.yml", false);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (reloadService != null) {
            reloadService.close();
        }
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        if (storageManager != null) {
            storageManager.shutdown();
            storageManager = null;
        }
    }
}
