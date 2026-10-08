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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static dev.plex.plexvariables.api.PlexVariablesApi.Scope.*;
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
}
