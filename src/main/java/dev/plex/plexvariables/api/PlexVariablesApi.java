package dev.plex.plexvariables.api;

import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface PlexVariablesApi {
    int API_VERSION = 1;

    enum Scope { PLAYER, GLOBAL }

    enum Status {
        SUCCESS, NO_CHANGE, MISSING_VALUE, NON_NUMERIC, VALUE_TOO_LONG, STORAGE_UNAVAILABLE, PERSISTENCE_FAILED
    }

    record VariableInfo(String id, boolean stored, Scope scope, String defaultValue) { }

    record MutationContext(String source, Optional<String> correlationId, Map<String, String> metadata) {
        public MutationContext {
            Objects.requireNonNull(source, "source");
            if (source.isBlank()) throw new IllegalArgumentException("Source must not be blank");
            Objects.requireNonNull(correlationId, "correlationId");
            metadata = Map.copyOf(metadata);
        }

        public MutationContext(String source) {
            this(source, Optional.empty(), Map.of());
        }
    }

    record VariableChange(String variable, Scope scope, Optional<UUID> playerId,
                          Optional<String> oldValue, Optional<String> newValue,
                          Optional<MutationContext> context) {
        public VariableChange {
            Objects.requireNonNull(variable, "variable");
            if (variable.isBlank()) throw new IllegalArgumentException("Variable must not be blank");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(oldValue, "oldValue");
            Objects.requireNonNull(newValue, "newValue");
            Objects.requireNonNull(context, "context");
            if ((scope == Scope.PLAYER) != playerId.isPresent()) {
                throw new IllegalArgumentException("Player scope requires a UUID; global scope cannot have one");
            }
        }
    }

    record MutationResult(Status status, Optional<VariableChange> change) {
        public MutationResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(change, "change");
            if ((status == Status.SUCCESS || status == Status.NO_CHANGE) != change.isPresent()) {
                throw new IllegalArgumentException("Only successful results contain a change");
            }
        }

        public boolean success() {
            return status == Status.SUCCESS || status == Status.NO_CHANGE;
        }
    }

    final class ReadException extends RuntimeException {
        private final Status status;

        public ReadException(Status status) {
            super("Stored value read failed: " + Objects.requireNonNull(status, "status"));
            if (status != Status.STORAGE_UNAVAILABLE && status != Status.PERSISTENCE_FAILED) {
                throw new IllegalArgumentException("Invalid read failure status");
            }
            this.status = status;
        }

        public Status status() {
            return status;
        }
    }

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    Optional<VariableInfo> variable(String id);

    String resolve(OfflinePlayer player, String id);

    CompletableFuture<Optional<String>> getStoredPlayerValue(UUID playerId, String variable);

    CompletableFuture<Optional<String>> getStoredGlobalValue(String variable);

    CompletableFuture<MutationResult> setStoredPlayerValue(UUID playerId, String variable, String value);

    CompletableFuture<MutationResult> setStoredPlayerValue(UUID playerId, String variable, String value, MutationContext context);

    CompletableFuture<MutationResult> setStoredGlobalValue(String variable, String value);

    CompletableFuture<MutationResult> setStoredGlobalValue(String variable, String value, MutationContext context);

    CompletableFuture<MutationResult> addStoredPlayerValue(UUID playerId, String variable, BigDecimal amount);

    CompletableFuture<MutationResult> addStoredPlayerValue(UUID playerId, String variable, BigDecimal amount, MutationContext context);

    CompletableFuture<MutationResult> addStoredGlobalValue(String variable, BigDecimal amount);

    CompletableFuture<MutationResult> addStoredGlobalValue(String variable, BigDecimal amount, MutationContext context);

    CompletableFuture<MutationResult> resetStoredPlayerValue(UUID playerId, String variable);

    CompletableFuture<MutationResult> resetStoredPlayerValue(UUID playerId, String variable, MutationContext context);

    CompletableFuture<MutationResult> resetStoredGlobalValue(String variable);

    CompletableFuture<MutationResult> resetStoredGlobalValue(String variable, MutationContext context);

    Subscription subscribe(Consumer<VariableChange> listener);
}
