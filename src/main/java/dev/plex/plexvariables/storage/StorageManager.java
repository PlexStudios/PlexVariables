package dev.plex.plexvariables.storage;

import dev.plex.plexvariables.config.PluginSettings;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class StorageManager {
    private final Path databasePath;
    private final Supplier<PluginSettings> settingsSupplier;
    private final Logger logger;

    private final SQLiteStorage sqliteStorage;
    private final GlobalVariableCache globalCache;
    private final PlayerVariableCache playerCache;

    private final ExecutorService executor;
    private volatile boolean shuttingDown = false;

    public StorageManager(Path databasePath, Supplier<PluginSettings> settingsSupplier, Logger logger) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");

        this.sqliteStorage = new SQLiteStorage(databasePath, logger);
        this.globalCache = new GlobalVariableCache();
        this.playerCache = new PlayerVariableCache();

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PlexVariables-StorageThread");
            t.setDaemon(true);
            return t;
        });
    }

    public void init() {
        sqliteStorage.init();
        Map<String, String> globals = sqliteStorage.loadGlobalVariables();
        globalCache.loadAll(globals);
    }

    public String getGlobalValue(String variableId) {
        return globalCache.get(variableId);
    }

    public String getPlayerValue(UUID uuid, String variableId) {
        return playerCache.getPlayerValue(uuid, variableId);
    }

    public PlayerVariableCache.CacheState getPlayerCacheState(UUID uuid) {
        return playerCache.getCacheState(uuid);
    }

    public CompletableFuture<Void> loadPlayerAsync(UUID uuid) {
        if (uuid == null || shuttingDown) {
            return CompletableFuture.completedFuture(null);
        }
        long version = playerCache.markLoading(uuid);
        return CompletableFuture.runAsync(() -> {
            Map<String, String> data = sqliteStorage.loadPlayerVariables(uuid);
            playerCache.setLoadedData(uuid, data, version);
        }, executor).exceptionally(ex -> {
            logger.warning("Failed to load stored player data for UUID " + uuid + ": " + ex.getMessage());
            return null;
        });
    }

    public void evictPlayer(UUID uuid) {
        playerCache.evictPlayer(uuid);
    }

    public CompletableFuture<Void> setPlayerValue(UUID uuid, String variableId, String value) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(variableId, "variableId");
        Objects.requireNonNull(value, "value");

        int limit = settingsSupplier.get().maxStorageValueLength();
        if (value.length() > limit) {
            return CompletableFuture.failedFuture(new StorageException("Value length exceeds limit of " + limit + " characters"));
        }

        if (shuttingDown) {
            return CompletableFuture.failedFuture(new StorageException("Storage is shutting down"));
        }

        return CompletableFuture.runAsync(() -> {
            long now = System.currentTimeMillis();
            sqliteStorage.savePlayerVariable(uuid, variableId, value, now);
            playerCache.setPlayerValue(uuid, variableId, value);
        }, executor);
    }

    public CompletableFuture<Void> deletePlayerValue(UUID uuid, String variableId) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(variableId, "variableId");

        if (shuttingDown) {
            return CompletableFuture.failedFuture(new StorageException("Storage is shutting down"));
        }

        return CompletableFuture.runAsync(() -> {
            sqliteStorage.deletePlayerVariable(uuid, variableId);
            playerCache.removePlayerValue(uuid, variableId);
        }, executor);
    }

    public CompletableFuture<Void> setGlobalValue(String variableId, String value) {
        Objects.requireNonNull(variableId, "variableId");
        Objects.requireNonNull(value, "value");

        int limit = settingsSupplier.get().maxStorageValueLength();
        if (value.length() > limit) {
            return CompletableFuture.failedFuture(new StorageException("Value length exceeds limit of " + limit + " characters"));
        }

        if (shuttingDown) {
            return CompletableFuture.failedFuture(new StorageException("Storage is shutting down"));
        }

        return CompletableFuture.runAsync(() -> {
            long now = System.currentTimeMillis();
            sqliteStorage.saveGlobalVariable(variableId, value, now);
            globalCache.set(variableId, value);
        }, executor);
    }

    public CompletableFuture<Void> deleteGlobalValue(String variableId) {
        Objects.requireNonNull(variableId, "variableId");

        if (shuttingDown) {
            return CompletableFuture.failedFuture(new StorageException("Storage is shutting down"));
        }

        return CompletableFuture.runAsync(() -> {
            sqliteStorage.deleteGlobalVariable(variableId);
            globalCache.remove(variableId);
        }, executor);
    }

    public CompletableFuture<Map<String, String>> fetchPlayerVariablesDirect(UUID uuid) {
        if (uuid == null) return CompletableFuture.completedFuture(Map.of());
        return CompletableFuture.supplyAsync(() -> sqliteStorage.loadPlayerVariables(uuid), executor);
    }

    public GlobalVariableCache globalCache() {
        return globalCache;
    }

    public PlayerVariableCache playerCache() {
        return playerCache;
    }

    public void shutdown() {
        shuttingDown = true;
        int timeoutSeconds = settingsSupplier.get().storageShutdownTimeoutSeconds();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("Storage executor did not terminate in " + timeoutSeconds + " seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            sqliteStorage.close();
        }
    }
}
