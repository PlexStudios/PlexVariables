package dev.plex.plexvariables.config;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ReloadService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigurationLoader loader;
    private final AtomicReference<PluginState> state;
    private final Runnable afterReload;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PlexVariables-config-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ReloadService(JavaPlugin plugin, ConfigurationLoader loader,
                         AtomicReference<PluginState> state, Runnable afterReload) {
        this.plugin = plugin;
        this.loader = loader;
        this.state = state;
        this.afterReload = afterReload;
    }

    public boolean reload(CommandSender sender) {
        if (closed.get()) {
            return false;
        }
        if (!busy.compareAndSet(false, true)) {
            state.get().messages().send(sender, "reload-in-progress");
            return false;
        }
        state.get().messages().send(sender, "reloading");
        long started = System.nanoTime();
        try {
            executor.execute(() -> load(sender, started));
        } catch (RejectedExecutionException exception) {
            busy.set(false);
            if (!closed.get()) {
                state.get().messages().send(sender, "reload-failed");
            }
            return false;
        }
        return true;
    }

    private void load(CommandSender sender, long started) {
        PluginState loaded = null;
        String failure = null;
        try {
            loaded = loader.load();
        } catch (IOException | RuntimeException exception) {
            failure = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
        if (closed.get()) {
            return;
        }
        PluginState result = loaded;
        String error = failure;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> finish(sender, started, result, error));
        } catch (IllegalPluginAccessException exception) {
            busy.set(false);
        }
    }

    private void finish(CommandSender sender, long started, PluginState loaded, String failure) {
        try {
            if (closed.get() || !plugin.isEnabled()) {
                return;
            }
            if (failure != null) {
                plugin.getLogger().warning("Reload failed; keeping the previous configuration: " + failure);
                state.get().messages().send(sender, "reload-failed");
                return;
            }
            state.set(loaded);
            afterReload.run();
            String elapsed = Long.toString((System.nanoTime() - started) / 1_000_000);
            plugin.getLogger().info("Reloaded " + loaded.variables().size() + " variables from "
                    + loaded.filesLoaded() + " files in " + elapsed + "ms.");
            loaded.messages().send(sender, "reload-success", Map.of(
                    "variables", Integer.toString(loaded.variables().size()), "time", elapsed));
        } finally {
            busy.set(false);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        executor.shutdownNow();
        busy.set(false);
    }
}
