package dev.plex.plexvariables.implementation;

import dev.plex.plexvariables.api.PlexVariablesApi;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.variable.VariableDefinition;
import dev.plex.plexvariables.variable.VariableResolver;
import dev.plex.plexvariables.variable.VariableType;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class DefaultPlexVariablesApi implements PlexVariablesApi, Listener, AutoCloseable {
    private final Supplier<PluginState> state;
    private final VariableResolver resolver;
    private final StorageManager storage;
    private final BooleanSupplier serverThread;
    private Plugin serviceOwner;
    private boolean closed;

    public DefaultPlexVariablesApi(Supplier<PluginState> state, VariableResolver resolver,
                                   StorageManager storage, BooleanSupplier serverThread) {
        this.state = Objects.requireNonNull(state);
        this.resolver = Objects.requireNonNull(resolver);
        this.storage = Objects.requireNonNull(storage);
        this.serverThread = Objects.requireNonNull(serverThread);
    }

    @Override
    public Optional<VariableInfo> variable(String id) {
        VariableDefinition definition = state.get().variables().get(normalize(id));
        return Optional.ofNullable(definition).map(value -> new VariableInfo(value.id(),
                value.type() == VariableType.STORED,
                value.scope() == null ? null : Scope.valueOf(value.scope().name()), value.defaultValue()));
    }

    @Override
    public String resolve(OfflinePlayer player, String id) {
        if (!serverThread.getAsBoolean()) throw new IllegalStateException("Variable resolution requires the server thread");
        return resolver.resolve(player, normalize(id));
    }

    @Override
    public CompletableFuture<Optional<String>> getStoredPlayerValue(UUID playerId, String variable) {
        return read(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"));
    }

    @Override
    public CompletableFuture<Optional<String>> getStoredGlobalValue(String variable) {
        return read(variable, Scope.GLOBAL, null);
    }

    private CompletableFuture<Optional<String>> read(String variable, Scope scope, UUID playerId) {
        return storage.readStored(validate(variable, scope).id(), scope, playerId);
    }

    @Override
    public CompletableFuture<MutationResult> setStoredPlayerValue(UUID playerId, String variable, String value) {
        return set(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"), value, null);
    }

    @Override
    public CompletableFuture<MutationResult> setStoredPlayerValue(UUID playerId, String variable, String value, MutationContext context) {
        return set(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"), value, Objects.requireNonNull(context, "context"));
    }

    @Override
    public CompletableFuture<MutationResult> setStoredGlobalValue(String variable, String value) {
        return set(variable, Scope.GLOBAL, null, value, null);
    }

    @Override
    public CompletableFuture<MutationResult> setStoredGlobalValue(String variable, String value, MutationContext context) {
        return set(variable, Scope.GLOBAL, null, value, Objects.requireNonNull(context, "context"));
    }

    @Override
    public CompletableFuture<MutationResult> addStoredPlayerValue(UUID playerId, String variable, BigDecimal amount) {
        return add(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"), amount, null);
    }

    @Override
    public CompletableFuture<MutationResult> addStoredPlayerValue(UUID playerId, String variable, BigDecimal amount, MutationContext context) {
        return add(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"), amount, Objects.requireNonNull(context, "context"));
    }

    @Override
    public CompletableFuture<MutationResult> addStoredGlobalValue(String variable, BigDecimal amount) {
        return add(variable, Scope.GLOBAL, null, amount, null);
    }

    @Override
    public CompletableFuture<MutationResult> addStoredGlobalValue(String variable, BigDecimal amount, MutationContext context) {
        return add(variable, Scope.GLOBAL, null, amount, Objects.requireNonNull(context, "context"));
    }

    @Override
    public CompletableFuture<MutationResult> resetStoredPlayerValue(UUID playerId, String variable) {
        return reset(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"), null);
    }

    @Override
    public CompletableFuture<MutationResult> resetStoredPlayerValue(UUID playerId, String variable, MutationContext context) {
        return reset(variable, Scope.PLAYER, Objects.requireNonNull(playerId, "playerId"), Objects.requireNonNull(context, "context"));
    }

    @Override
    public CompletableFuture<MutationResult> resetStoredGlobalValue(String variable) {
        return reset(variable, Scope.GLOBAL, null, null);
    }

    @Override
    public CompletableFuture<MutationResult> resetStoredGlobalValue(String variable, MutationContext context) {
        return reset(variable, Scope.GLOBAL, null, Objects.requireNonNull(context, "context"));
    }

    private CompletableFuture<MutationResult> set(String variable, Scope scope, UUID playerId,
                                                   String value, MutationContext context) {
        VariableDefinition definition = validate(variable, scope);
        Objects.requireNonNull(value, "value");
        return storage.mutateStored(definition.id(), scope, playerId, old -> value, definition.defaultValue(), context);
    }

    private CompletableFuture<MutationResult> add(String variable, Scope scope, UUID playerId,
                                                   BigDecimal amount, MutationContext context) {
        VariableDefinition definition = validate(variable, scope);
        Objects.requireNonNull(amount, "amount");
        return storage.addStored(definition.id(), scope, playerId, amount, definition.defaultValue(), definition.defaultValue(), context);
    }

    private CompletableFuture<MutationResult> reset(String variable, Scope scope, UUID playerId,
                                                     MutationContext context) {
        VariableDefinition definition = validate(variable, scope);
        return storage.mutateStored(definition.id(), scope, playerId, old -> null, definition.defaultValue(), context);
    }

    @Override
    public Subscription subscribe(Consumer<VariableChange> listener) {
        return storage.subscribe(listener);
    }

    @Override
    public Subscription subscribe(Plugin owner, Consumer<VariableChange> listener) {
        Objects.requireNonNull(owner, "owner");
        if (!serverThread.getAsBoolean()) throw new IllegalStateException("Owned subscriptions require the server thread");
        if (!owner.isEnabled()) throw new IllegalArgumentException("Subscription owner is disabled");
        return storage.subscribe(owner, listener);
    }

    public synchronized void register(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (closed || serviceOwner != null) throw new IllegalStateException("Provider is closed or already registered");
        serviceOwner = owner;
        try {
            owner.getServer().getPluginManager().registerEvents(this, owner);
            owner.getServer().getServicesManager().register(PlexVariablesApi.class, this, owner, ServicePriority.Normal);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        storage.unsubscribeOwner(event.getPlugin());
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        storage.closeSubscriptions();
        HandlerList.unregisterAll(this);
        if (serviceOwner != null) {
            serviceOwner.getServer().getServicesManager().unregister(PlexVariablesApi.class, this);
            serviceOwner = null;
        }
    }

    private VariableDefinition validate(String variable, Scope scope) {
        VariableDefinition definition = state.get().variables().get(normalize(variable));
        if (definition == null || definition.type() != VariableType.STORED) {
            throw new IllegalArgumentException("Unknown stored variable: " + variable);
        }
        if (!definition.scope().name().equals(scope.name())) throw new IllegalArgumentException("Variable scope mismatch");
        return definition;
    }

    private String normalize(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("Variable ID must not be blank");
        return id.toLowerCase(Locale.ROOT);
    }
}
