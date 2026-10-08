package dev.plex.plexvariables.storage;

import dev.plex.plexvariables.config.PluginSettings;
import org.bukkit.plugin.Plugin;
import dev.plex.plexvariables.api.PlexVariablesApi.Scope;
import dev.plex.plexvariables.api.PlexVariablesApi.MutationContext;
import dev.plex.plexvariables.api.PlexVariablesApi.MutationResult;
import dev.plex.plexvariables.api.PlexVariablesApi.Status;
import dev.plex.plexvariables.api.PlexVariablesApi.ReadException;
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
    private final CopyOnWriteArrayList<Registration> listeners = new CopyOnWriteArrayList<>();
    private boolean subscriptionsClosed;

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
        return setPlayerValue(uuid, variableId, value, null);
    }

    public CompletableFuture<Void> setPlayerValue(UUID uuid, String variableId, String value, String defaultValue) {
        Objects.requireNonNull(value, "value");
        return mutateStored(variableId, Scope.PLAYER, uuid, old -> value, defaultValue, null)
                .thenApply(this::requireChange).thenApply(change -> null);
    }

    public CompletableFuture<Void> deletePlayerValue(UUID uuid, String variableId) {
        return deletePlayerValue(uuid, variableId, null);
    }

    public CompletableFuture<Void> deletePlayerValue(UUID uuid, String variableId, String defaultValue) {
        return mutateStored(variableId, Scope.PLAYER, uuid, old -> null, defaultValue, null)
                .thenApply(this::requireChange).thenApply(change -> null);
    }

    public CompletableFuture<Void> setGlobalValue(String variableId, String value) {
        return setGlobalValue(variableId, value, null);
    }

    public CompletableFuture<Void> setGlobalValue(String variableId, String value, String defaultValue) {
        Objects.requireNonNull(value, "value");
        return mutateStored(variableId, Scope.GLOBAL, null, old -> value, defaultValue, null)
                .thenApply(this::requireChange).thenApply(change -> null);
    }

    public CompletableFuture<Void> deleteGlobalValue(String variableId) {
        return deleteGlobalValue(variableId, null);
    }

    public CompletableFuture<Void> deleteGlobalValue(String variableId, String defaultValue) {
        return mutateStored(variableId, Scope.GLOBAL, null, old -> null, defaultValue, null)
                .thenApply(this::requireChange).thenApply(change -> null);
    }

    public CompletableFuture<Optional<String>> readStored(String variable, Scope scope, UUID playerId) {
        validateTarget(variable, scope, playerId);
        return submit(() -> Optional.ofNullable(sqliteStorage.readVariable(variable, playerId)))
                .handle((value, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        throw new ReadException(cause instanceof UnavailableException
                                ? Status.STORAGE_UNAVAILABLE : Status.PERSISTENCE_FAILED);
                    }
                    return value;
                });
    }

    public CompletableFuture<VariableChange> addStoredCommand(String variable, Scope scope, UUID playerId,
                                                               BigDecimal amount, String startingDefault, String effectiveDefault) {
        return addStored(variable, scope, playerId, amount, startingDefault, effectiveDefault, null)
                .thenApply(this::requireChange);
    }

    private VariableChange requireChange(MutationResult result) {
        if (!result.success()) {
            String message = switch (result.status()) {
                case MISSING_VALUE -> "No stored value or configured numeric default is available";
                case NON_NUMERIC -> "Stored value is not numeric";
                case VALUE_TOO_LONG -> "Value length exceeds limit of " + settingsSupplier.get().maxStorageValueLength() + " characters";
                case STORAGE_UNAVAILABLE -> "Storage is shutting down";
                default -> "Failed to persist stored value";
            };
            throw new StorageException(message);
        }
        return result.change().orElseThrow();
    }

    public CompletableFuture<MutationResult> addStored(String variable, Scope scope, UUID playerId,
                                                       BigDecimal amount, String startingDefault, String effectiveDefault,
                                                       MutationContext context) {
        Objects.requireNonNull(amount, "amount");
        return mutateStored(variable, scope, playerId, old -> {
            String current = old == null ? startingDefault : old;
            if (current == null) throw new MutationFailure(Status.MISSING_VALUE);
            try {
                return new BigDecimal(current.trim()).add(amount).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException exception) {
                throw new MutationFailure(Status.NON_NUMERIC);
            }
        }, effectiveDefault, context);
    }

    public CompletableFuture<MutationResult> mutateStored(String variable, Scope scope, UUID playerId,
                                                          UnaryOperator<String> mutation, String defaultValue,
                                                          MutationContext context) {
        validateTarget(variable, scope, playerId);
        Objects.requireNonNull(mutation, "mutation");
        return submit(() -> {
            String old = sqliteStorage.readVariable(variable, playerId);
            String value = mutation.apply(old);
            int limit = settingsSupplier.get().maxStorageValueLength();
            if (value != null && value.length() > limit) throw new MutationFailure(Status.VALUE_TOO_LONG);
            VariableChange change = new VariableChange(variable, scope, Optional.ofNullable(playerId),
                    Optional.ofNullable(old), Optional.ofNullable(value), Optional.ofNullable(context));
            if (Objects.equals(old, value)) return new MutationResult(Status.NO_CHANGE, Optional.of(change));
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
            String oldEffective = old == null ? defaultValue : old;
            String newEffective = value == null ? defaultValue : value;
            if (!Objects.equals(oldEffective, newEffective)) {
                for (Registration listener : listeners) listener.accept(change);
            }
            return new MutationResult(Status.SUCCESS, Optional.of(change));
        }).handle((result, failure) -> {
            if (failure == null) return result;
            Throwable cause = unwrap(failure);
            Status status;
            if (cause instanceof MutationFailure rejected) status = rejected.status;
            else if (cause instanceof UnavailableException) status = Status.STORAGE_UNAVAILABLE;
            else {
                status = Status.PERSISTENCE_FAILED;
                logger.warning("Stored variable mutation failed (" + cause.getClass().getName() + ")");
            }
            return new MutationResult(status, Optional.empty());
        });
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static final class MutationFailure extends RuntimeException {
        private final Status status;

        private MutationFailure(Status status) {
            this.status = status;
        }
    }

    private static final class UnavailableException extends StorageException {
        private UnavailableException() {
            super("Storage is shutting down");
        }
    }
    public Subscription subscribe(Consumer<VariableChange> listener) {
        return subscribe(null, listener);
    }

    public synchronized Subscription subscribe(Plugin owner, Consumer<VariableChange> listener) {
        Objects.requireNonNull(listener, "listener");
        if (shuttingDown || subscriptionsClosed) throw new IllegalStateException("Subscriptions are closed");
        Registration registration = new Registration(owner, listener, logger);
        registration.removal = () -> listeners.remove(registration);
        listeners.add(registration);
        return registration;
    }

    public synchronized void unsubscribeOwner(Plugin owner) {
        for (Registration registration : listeners) {
            if (registration.ownedBy(owner)) registration.close();
        }
    }

    public synchronized void closeSubscriptions() {
        subscriptionsClosed = true;
        for (Registration registration : listeners) registration.close();
        listeners.clear();
    }

    static final class Registration implements Subscription {
        Plugin owner;
        Consumer<VariableChange> listener;
        Runnable removal;
        Logger logger;
        private boolean failureLogged;

        private Registration(Plugin owner, Consumer<VariableChange> listener, Logger logger) {
            this.owner = owner;
            this.listener = listener;
            this.logger = logger;
        }

        synchronized boolean ownedBy(Plugin plugin) {
            return owner == plugin;
        }

        void accept(VariableChange change) {
            Consumer<VariableChange> callback;
            Logger diagnostics;
            synchronized (this) {
                callback = listener;
                diagnostics = logger;
            }
            if (callback == null) return;
            try {
                callback.accept(change);
            } catch (Throwable exception) {
                synchronized (this) {
                    if (failureLogged) return;
                    failureLogged = true;
                }
                diagnostics.warning("Stored variable subscriber failed (" + exception.getClass().getName() + ")");
            }
        }

        @Override
        public void close() {
            Runnable remove;
            synchronized (this) {
                remove = removal;
                removal = null;
                listener = null;
                owner = null;
                logger = null;
            }
            if (remove != null) remove.run();
        }
    }
    private void validateTarget(String variable, Scope scope, UUID playerId) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(scope, "scope");
        if (variable.isBlank()) throw new IllegalArgumentException("Variable ID must not be blank");
        if ((scope == Scope.PLAYER) != (playerId != null)) throw new IllegalArgumentException("Invalid scope and UUID combination");
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        if (shuttingDown) return CompletableFuture.failedFuture(new UnavailableException());
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new UnavailableException());
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
            closeSubscriptions();
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
