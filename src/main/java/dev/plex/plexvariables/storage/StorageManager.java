package dev.plex.plexvariables.storage;

import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.api.PlexVariablesApi.Scope;
import dev.plex.plexvariables.api.PlexVariablesApi.MutationContext;
import dev.plex.plexvariables.api.PlexVariablesApi.Subscription;
import dev.plex.plexvariables.api.PlexVariablesApi.VariableChange;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

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
    private final CopyOnWriteArrayList<Consumer<VariableChange>> listeners = new CopyOnWriteArrayList<>();

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
        Objects.requireNonNull(value, "value");
        return mutateStored(variableId, Scope.PLAYER, uuid, old -> value, null).thenApply(change -> null);
    }

    public CompletableFuture<Void> deletePlayerValue(UUID uuid, String variableId) {
        return mutateStored(variableId, Scope.PLAYER, uuid, old -> null, null).thenApply(change -> null);
    }

    public CompletableFuture<Void> setGlobalValue(String variableId, String value) {
        Objects.requireNonNull(value, "value");
        return mutateStored(variableId, Scope.GLOBAL, null, old -> value, null).thenApply(change -> null);
    }

    public CompletableFuture<Void> deleteGlobalValue(String variableId) {
        return mutateStored(variableId, Scope.GLOBAL, null, old -> null, null).thenApply(change -> null);
    }

    public CompletableFuture<Optional<String>> readStored(String variable, Scope scope, UUID playerId) {
        validateTarget(variable, scope, playerId);
        return submit(() -> Optional.ofNullable(sqliteStorage.readVariable(variable, scope == Scope.PLAYER ? playerId : null)));
    }

    public CompletableFuture<VariableChange> addStored(String variable, Scope scope, UUID playerId,
                                                       BigDecimal amount, String defaultValue, MutationContext cause) {
        Objects.requireNonNull(amount, "amount");
        return mutateStored(variable, scope, playerId, old -> {
            String current = old == null ? defaultValue : old;
            if (current == null) throw new StorageException("No stored value or configured numeric default is available");
            BigDecimal number = new BigDecimal(current.trim());
            return number.add(amount).stripTrailingZeros().toPlainString();
        }, cause);
    }

    public CompletableFuture<VariableChange> mutateStored(String variable, Scope scope, UUID playerId,
                                                         UnaryOperator<String> mutation, MutationContext cause) {
        validateTarget(variable, scope, playerId);
        Objects.requireNonNull(mutation, "mutation");
        return submit(() -> {
            String old = sqliteStorage.readVariable(variable, playerId);
            String value = mutation.apply(old);
            int limit = settingsSupplier.get().maxStorageValueLength();
            if (value != null && value.length() > limit) {
                throw new StorageException("Value length exceeds limit of " + limit + " characters");
            }
            VariableChange change = new VariableChange(variable, scope, Optional.ofNullable(playerId), Optional.ofNullable(old), Optional.ofNullable(value), Optional.ofNullable(cause));
            if (Objects.equals(old, value)) return change;
            if (scope == Scope.PLAYER) {
                if (value == null) sqliteStorage.deletePlayerVariable(playerId, variable);
                else sqliteStorage.savePlayerVariable(playerId, variable, value, System.currentTimeMillis());
                if (value == null) playerCache.removePlayerValue(playerId, variable);
                else if (playerCache.getCacheState(playerId) == PlayerVariableCache.CacheState.LOADED) {
                    playerCache.setPlayerValue(playerId, variable, value);
                }
            } else {
                if (value == null) sqliteStorage.deleteGlobalVariable(variable);
                else sqliteStorage.saveGlobalVariable(variable, value, System.currentTimeMillis());
                if (value == null) globalCache.remove(variable);
                else globalCache.set(variable, value);
            }
            for (Consumer<VariableChange> listener : listeners) listener.accept(change);
            return change;
        });
    }

    public synchronized Subscription subscribe(Consumer<VariableChange> listener) {
        Objects.requireNonNull(listener, "listener");
        if (shuttingDown) throw new IllegalStateException("Storage is shutting down");
        java.util.concurrent.atomic.AtomicBoolean failureLogged = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<VariableChange> registration = change -> {
            try {
                listener.accept(change);
            } catch (RuntimeException exception) {
                if (failureLogged.compareAndSet(false, true)) {
                    logger.warning("Stored variable subscriber failed (" + exception.getClass().getName() + ")");
                }
            }
        };
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    private void validateTarget(String variable, Scope scope, UUID playerId) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(scope, "scope");
        if (variable.isBlank()) throw new IllegalArgumentException("Variable ID must not be blank");
        if ((scope == Scope.PLAYER) != (playerId != null)) throw new IllegalArgumentException("Invalid scope and UUID combination");
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        if (shuttingDown) return CompletableFuture.failedFuture(new StorageException("Storage is shutting down"));
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new StorageException("Storage is shutting down", exception));
        }
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

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public void shutdown() {
        synchronized (this) {
            shuttingDown = true;
            listeners.clear();
        }
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
