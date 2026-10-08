package dev.plex.plexvariables.api;

import dev.plex.plexvariables.implementation.DefaultPlexVariablesApi;
import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.storage.StoredVariableScope;
import dev.plex.plexvariables.util.MessageUtil;
import dev.plex.plexvariables.variable.VariableDefinition;
import dev.plex.plexvariables.variable.VariableResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static dev.plex.plexvariables.api.PlexVariablesApi.Scope.*;
import static dev.plex.plexvariables.api.PlexVariablesApi.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class PlexVariablesApiTest {
    @TempDir Path directory;
    private StorageManager storage;
    private PlexVariablesApi api;
    private PluginState state;
    private VariableResolver resolver;

    @BeforeEach
    void start() {
        Logger logger = Logger.getLogger(getClass().getName());
        storage = new StorageManager(directory.resolve("data.db"), PluginSettings::defaults, logger);
        storage.init();
        state = new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), Map.of(
                "score", VariableDefinition.ofStored("score", StoredVariableScope.PLAYER, "10", "test.yml"),
                "total", VariableDefinition.ofStored("total", StoredVariableScope.GLOBAL, "0", "test.yml"),
                "unset", VariableDefinition.ofStored("unset", StoredVariableScope.GLOBAL, null, "test.yml"),
                "label", VariableDefinition.ofStatic("label", "ready", "test.yml")), 1);
        resolver = new VariableResolver(() -> state, (player, text) -> text, storage, logger);
        api = new DefaultPlexVariablesApi(() -> state, resolver, storage, () -> true);
    }

    @AfterEach
    void stop() {
        storage.shutdown();
    }

    @Test
    void metadataAndResolutionUseDefinitions() {
        assertEquals(PLAYER, api.variable("SCORE").orElseThrow().scope());
        assertFalse(api.variable("label").orElseThrow().stored());
        assertTrue(api.variable("absent").isEmpty());
        assertEquals("ready", api.resolve(null, "label"));
        PlexVariablesApi async = new DefaultPlexVariablesApi(() -> state, resolver, storage, () -> false);
        assertThrows(IllegalStateException.class, () -> async.resolve(null, "label"));
    }

    @Test
    void invalidTargetsAreRejected() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> api.setStoredGlobalValue("missing", "1"));
        assertThrows(IllegalArgumentException.class, () -> api.resetStoredGlobalValue("label"));
        assertThrows(IllegalArgumentException.class, () -> api.getStoredGlobalValue("score"));
        assertThrows(NullPointerException.class, () -> api.getStoredPlayerValue(null, "score"));
        assertThrows(IllegalArgumentException.class, () -> api.getStoredPlayerValue(player, "total"));
        assertThrows(NullPointerException.class, () -> api.setStoredGlobalValue("total", null));
    }

    @Test
    void offlineReadsAndAddsUsePersistence() throws Exception {
        UUID player = UUID.randomUUID();
        api.setStoredPlayerValue(player, "score", "42").get(5, TimeUnit.SECONDS);
        storage.evictPlayer(player);
        assertEquals("42", api.getStoredPlayerValue(player, "score").get(5, TimeUnit.SECONDS).orElseThrow());
        assertEquals("44", api.addStoredPlayerValue(player, "score", new BigDecimal("2")).get(5, TimeUnit.SECONDS).change().orElseThrow().newValue().orElseThrow());
        storage.loadPlayerAsync(player).get(5, TimeUnit.SECONDS);
        assertEquals("44", storage.getPlayerValue(player, "score"));
    }

    @Test
    void missingApiValueAndDefaultFailWhileExplicitCommandFallbackRemainsSupported() throws Exception {
        assertFalse(api.addStoredGlobalValue("unset", BigDecimal.ONE).get(5, TimeUnit.SECONDS).success());
        assertTrue(api.getStoredGlobalValue("unset").get(5, TimeUnit.SECONDS).isEmpty());
        org.bukkit.command.CommandSender sender = org.mockito.Mockito.mock(org.bukkit.command.CommandSender.class);
        org.mockito.Mockito.when(sender.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var command = new dev.plex.plexvariables.command.PlexVariablesCommand(() -> state, resolver, null,
                storage, org.mockito.Mockito.mock(org.bukkit.Server.class), Logger.getAnonymousLogger());
        command.onCommand(sender, null, "pv", new String[]{"add", "unset", "global", "1"});
        assertEquals("1", api.getStoredGlobalValue("unset").get(5, TimeUnit.SECONDS).orElseThrow());
    }

    @Test
    void concurrentAddsDoNotLoseUpdates() throws Exception {
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (int index = 0; index < 100; index++) writes.add(api.addStoredGlobalValue("total", BigDecimal.ONE));
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals("100", api.getStoredGlobalValue("total").get(5, TimeUnit.SECONDS).orElseThrow());
    }

    @Test
    void changesPublishAfterPersistenceAndCacheWithCause() throws Exception {
        var cause = new PlexVariablesApi.MutationContext("ExamplePlugin");
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var subscription = api.subscribe(change -> {
            assertEquals(change.newValue().orElse(null), storage.getGlobalValue(change.variable()));
            changes.add(change);
        })) {
            var first = api.setStoredGlobalValue("total", "5", cause).get(5, TimeUnit.SECONDS);
            assertTrue(first.change().orElseThrow().oldValue().isEmpty());
            assertSame(cause, changes.getFirst().context().orElseThrow());
            api.setStoredGlobalValue("total", "5").get(5, TimeUnit.SECONDS);
            storage.setGlobalValue("total", "6").get(5, TimeUnit.SECONDS);
            var reset = api.resetStoredGlobalValue("total", cause).get(5, TimeUnit.SECONDS);
            assertEquals("6", reset.change().orElseThrow().oldValue().orElseThrow());
            assertTrue(reset.change().orElseThrow().newValue().isEmpty());
            api.resetStoredGlobalValue("total", cause).get(5, TimeUnit.SECONDS);
            assertEquals(3, changes.size());
        }
        api.setStoredGlobalValue("total", "7").get(5, TimeUnit.SECONDS);
        assertEquals(3, changes.size());
    }

    @Test
    void invalidNumbersAndOversizedValuesDoNotPersistOrNotify() throws Exception {
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var subscription = api.subscribe(changes::add)) {
            api.setStoredGlobalValue("total", "text").get(5, TimeUnit.SECONDS);
            assertFalse(api.addStoredGlobalValue("total", BigDecimal.ONE).get(5, TimeUnit.SECONDS).success());
            assertFalse(api.setStoredGlobalValue("total", "x".repeat(5000)).get(5, TimeUnit.SECONDS).success());
            assertEquals("text", api.getStoredGlobalValue("total").get(5, TimeUnit.SECONDS).orElseThrow());
            assertEquals(1, changes.size());
        }
    }

    @Test
    void shutdownRejectsWorkAndSubscriptions() {
        storage.shutdown();
        assertThrows(IllegalStateException.class, () -> api.subscribe(change -> { }));
        assertTrue(api.getStoredGlobalValue("total").isCompletedExceptionally());
    }

    @Test
    void subscriberDiagnosticsAreRedactedAndLoggedOnce() throws Exception {
        Logger logger = org.mockito.Mockito.mock(Logger.class);
        StorageManager separate = new StorageManager(directory.resolve("logging.db"), PluginSettings::defaults, logger);
        separate.init();
        try {
            try (var subscription = separate.subscribe(change -> { throw new IllegalArgumentException("private subscriber payload"); })) {
                separate.setGlobalValue("value", "one").get(5, TimeUnit.SECONDS);
                separate.setGlobalValue("value", "two").get(5, TimeUnit.SECONDS);
            }
            org.mockito.Mockito.verify(logger).warning("Stored variable subscriber failed (java.lang.IllegalArgumentException)");
            org.mockito.Mockito.verifyNoMoreInteractions(logger);
        } finally {
            separate.shutdown();
        }
    }

    @Test
    void subscriberFailureDoesNotFailCommittedWrite() throws Exception {
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var broken = api.subscribe(change -> { throw new IllegalStateException("subscriber failure"); });
             var working = api.subscribe(changes::add)) {
            api.setStoredGlobalValue("total", "1").get(5, TimeUnit.SECONDS);
            assertEquals(1, changes.size());
        }
    }

    @Test
    void rawTransitionsToAndFromDefaultPersistWithoutEffectiveNotifications() throws Exception {
        UUID player = UUID.randomUUID();
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var subscription = api.subscribe(changes::add)) {
            assertEquals(SUCCESS, api.setStoredPlayerValue(player, "score", "10").get().status());
            assertEquals(Optional.of("10"), api.getStoredPlayerValue(player, "score").get());
            var reset = api.resetStoredPlayerValue(player, "score").get();
            assertEquals(SUCCESS, reset.status());
            assertEquals(Optional.of("10"), reset.change().orElseThrow().oldValue());
            assertTrue(reset.change().orElseThrow().newValue().isEmpty());
            assertEquals(NO_CHANGE, api.resetStoredPlayerValue(player, "score").get().status());
            assertEquals(SUCCESS, api.setStoredGlobalValue("total", "0").get().status());
            assertEquals(SUCCESS, api.resetStoredGlobalValue("total").get().status());
            assertTrue(changes.isEmpty());
        }
    }

    @Test
    void numericAndLimitFailuresHaveSpecificStatuses() throws Exception {
        assertEquals(MISSING_VALUE, api.addStoredGlobalValue("unset", BigDecimal.ONE).get().status());
        api.setStoredGlobalValue("total", "garbage").get();
        assertEquals(NON_NUMERIC, api.addStoredGlobalValue("total", BigDecimal.ONE).get().status());
        assertEquals(VALUE_TOO_LONG, api.setStoredGlobalValue("total", "x".repeat(5000)).get().status());
        assertEquals("garbage", api.getStoredGlobalValue("total").get().orElseThrow());
        UUID player = UUID.randomUUID();
        assertEquals("9.25", api.addStoredPlayerValue(player, "score", new BigDecimal("-0.75"))
                .get().change().orElseThrow().newValue().orElseThrow());
        assertEquals(NO_CHANGE, api.addStoredPlayerValue(player, "score", BigDecimal.ZERO).get().status());
    }

    @Test
    void representationChangesRemainVisibleAndContextIsOptional() throws Exception {
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var subscription = api.subscribe(changes::add)) {
            api.setStoredGlobalValue("total", "1.0").get();
            var context = new PlexVariablesApi.MutationContext("ExamplePlugin", Optional.of("request"), Map.of("key", "value"));
            var result = api.addStoredGlobalValue("total", BigDecimal.ZERO, context).get();
            assertEquals(SUCCESS, result.status());
            assertEquals(Optional.of("1"), result.change().orElseThrow().newValue());
            assertEquals(2, changes.size());
            assertTrue(changes.getFirst().context().isEmpty());
            assertEquals(Optional.of(context), changes.getLast().context());
        }
    }

    @Test
    void failedWriteDoesNotUpdateCacheOrNotify() throws Exception {
        api.setStoredGlobalValue("total", "5").get();
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data.db"));
             var statement = connection.createStatement();
             var subscription = api.subscribe(changes::add)) {
            statement.execute("CREATE TRIGGER reject_write BEFORE UPDATE ON global_variables BEGIN SELECT RAISE(ABORT, 'test write rejected'); END");
            var result = api.setStoredGlobalValue("total", "6").get();
            assertEquals(PERSISTENCE_FAILED, result.status());
            assertTrue(result.change().isEmpty());
            assertEquals("5", storage.getGlobalValue("total"));
            assertEquals(Optional.of("5"), api.getStoredGlobalValue("total").get());
            assertTrue(changes.isEmpty());
            statement.execute("DROP TRIGGER reject_write");
        }
    }

    @Test
    void failedReadDoesNotFabricateChangeAndUsesPublicReadException() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data.db"));
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE global_variables");
            var result = api.setStoredGlobalValue("total", "6").get();
            assertEquals(PERSISTENCE_FAILED, result.status());
            assertTrue(result.change().isEmpty());
            var failure = assertThrows(ExecutionException.class, () -> api.getStoredGlobalValue("total").get());
            assertInstanceOf(PlexVariablesApi.ReadException.class, failure.getCause());
            assertNull(failure.getCause().getCause());
        }
    }

    @Test
    void callbackRunsOnExecutorAfterCommitVisibleToAnotherConnection() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var subscription = api.subscribe(change -> {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data.db"));
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT value FROM global_variables WHERE variable_id = 'total'")) {
                assertNotSame(caller, Thread.currentThread());
                assertEquals("PlexVariables-StorageThread", Thread.currentThread().getName());
                assertTrue(rows.next());
                assertEquals("7", rows.getString(1));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        })) {
            assertEquals(SUCCESS, api.setStoredGlobalValue("total", "7").get().status());
            assertNull(failure.get());
        }
    }

    @Test
    void queuedMutationUsesSubmittedDefinitionSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var subscription = api.subscribe(change -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        })) {
            var first = api.setStoredGlobalValue("unset", "hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = api.addStoredPlayerValue(UUID.randomUUID(), "score", BigDecimal.ONE);
            state = new PluginState(state.settings(), state.messages(), Map.of(
                    "score", VariableDefinition.ofStored("score", StoredVariableScope.PLAYER, "100", "test.yml")), 1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("11"), queued.get(5, TimeUnit.SECONDS).change().orElseThrow().newValue());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownMutationReturnsUnavailable() throws Exception {
        storage.shutdown();
        assertEquals(STORAGE_UNAVAILABLE, api.setStoredGlobalValue("total", "1").get().status());
    }

    @Test
    void allContextOverloadsPreservePlayerAndGlobalIdentity() throws Exception {
        UUID player = UUID.randomUUID();
        var context = new PlexVariablesApi.MutationContext("ExamplePlugin");
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var subscription = api.subscribe(changes::add)) {
            api.setStoredPlayerValue(player, "score", "20", context).get();
            api.addStoredPlayerValue(player, "score", BigDecimal.ONE, context).get();
            api.resetStoredPlayerValue(player, "score", context).get();
            api.setStoredGlobalValue("total", "20", context).get();
            api.addStoredGlobalValue("total", BigDecimal.ONE, context).get();
            api.resetStoredGlobalValue("total", context).get();
            assertEquals(6, changes.size());
            for (var change : changes) {
                assertEquals(Optional.of(context), change.context());
                assertEquals(change.scope() == PLAYER ? Optional.of(player) : Optional.empty(), change.playerId());
            }
            assertTrue(api.getStoredPlayerValue(player, "score").get().isEmpty());
            assertTrue(api.getStoredGlobalValue("total").get().isEmpty());
        }
    }

    @Test
    void malformedDefaultAndInvalidArgumentsAreRejected() throws Exception {
        state = new PluginState(state.settings(), state.messages(), Map.of(
                "text", VariableDefinition.ofStored("text", StoredVariableScope.GLOBAL, "invalid", "test.yml")), 1);
        assertEquals(NON_NUMERIC, api.addStoredGlobalValue("text", BigDecimal.ONE).get().status());
        assertTrue(api.getStoredGlobalValue("text").get().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> api.getStoredGlobalValue(" "));
        assertThrows(NullPointerException.class, () -> api.getStoredGlobalValue(null));
        assertThrows(NullPointerException.class, () -> api.addStoredGlobalValue("text", null));
        assertThrows(NullPointerException.class, () -> api.resetStoredGlobalValue("text", null));
    }

    @Test
    void forcedShutdownSettlesQueuedOperationsWithoutMisreportingCommittedWrite() throws Exception {
        var config = new YamlConfiguration();
        config.set("settings.storage.shutdown-timeout-seconds", 1);
        var settings = PluginSettings.from(config);
        var separate = new StorageManager(directory.resolve("shutdown.db"), () -> settings, Logger.getAnonymousLogger());
        separate.init();
        var provider = new DefaultPlexVariablesApi(() -> state, resolver, separate, () -> true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        separate.subscribe(change -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var committed = provider.setStoredGlobalValue("total", "1");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queuedWrite = provider.setStoredGlobalValue("total", "2");
            var queuedRead = provider.getStoredGlobalValue("total");
            separate.shutdown();
            assertEquals(SUCCESS, committed.get(5, TimeUnit.SECONDS).status());
            assertEquals(STORAGE_UNAVAILABLE, queuedWrite.get(5, TimeUnit.SECONDS).status());
            var failure = assertThrows(ExecutionException.class, () -> queuedRead.get(5, TimeUnit.SECONDS));
            assertEquals(STORAGE_UNAVAILABLE, ((PlexVariablesApi.ReadException) failure.getCause()).status());
        } finally {
            release.countDown();
            separate.shutdown();
        }
    }
}
