package dev.plex.plexvariables.api;

import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.implementation.DefaultPlexVariablesApi;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.storage.StoredVariableScope;
import dev.plex.plexvariables.util.MessageUtil;
import dev.plex.plexvariables.variable.VariableDefinition;
import dev.plex.plexvariables.variable.VariableResolver;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiLifecycleTest {
    @TempDir Path directory;
    private StorageManager storage;
    private DefaultPlexVariablesApi api;
    private Plugin owner;
    private MockedStatic<Bukkit> bukkit;
    private final AtomicBoolean mainThread = new AtomicBoolean(true);

    @BeforeEach
    void start() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        Server eventServer = mock(Server.class);
        when(eventServer.getPluginManager()).thenReturn(mock(PluginManager.class));
        bukkit.when(Bukkit::getServer).thenReturn(eventServer);
        Logger logger = Logger.getAnonymousLogger();
        storage = new StorageManager(directory.resolve("data.db"), PluginSettings::defaults, logger);
        storage.init();
        var state = new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), Map.of(
                "total", VariableDefinition.ofStored("total", StoredVariableScope.GLOBAL, "0", "test.yml")), 1);
        var resolver = new VariableResolver(() -> state, (player, text) -> text, storage, logger);
        api = new DefaultPlexVariablesApi(() -> state, resolver, storage, mainThread::get);
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void stop() {
        api.close();
        storage.shutdown();
        bukkit.close();
    }

    @Test
    void registersDiscoverableServiceAndCleansUpOnClose() {
        var services = new SimpleServicesManager();
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getServicesManager()).thenReturn(services);
        assertNull(services.load(PlexVariablesApi.class));
        api.register(owner);
        assertSame(api, services.load(PlexVariablesApi.class));
        assertSame(owner, services.getRegistration(PlexVariablesApi.class).getPlugin());
        verify(manager).registerEvents(api, owner);
        api.close();
        api.close();
        assertNull(services.load(PlexVariablesApi.class));
        assertThrows(IllegalStateException.class, () -> api.register(owner));
    }

    @Test
    void failedPublicationCleansListenersAndPreventsRegistration() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        var services = spy(new SimpleServicesManager());
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getServicesManager()).thenReturn(services);
        doThrow(new IllegalStateException("publication failure")).when(services)
                .register(eq(PlexVariablesApi.class), same(api), same(owner), any());
        AtomicInteger callbacks = new AtomicInteger();
        api.subscribe(change -> callbacks.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> api.register(owner));
        assertNull(services.load(PlexVariablesApi.class));
        assertThrows(IllegalStateException.class, () -> api.subscribe(change -> { }));
        storage.setGlobalValue("total", "1").join();
        assertEquals(0, callbacks.get());
    }

    @Test
    void ownerDisableRemovesOnlyItsSubscriptions() throws Exception {
        Plugin other = mock(Plugin.class);
        when(other.isEnabled()).thenReturn(true);
        List<PlexVariablesApi.VariableChange> owned = new ArrayList<>();
        List<PlexVariablesApi.VariableChange> remaining = new ArrayList<>();
        var handle = api.subscribe(owner, owned::add);
        try (var second = api.subscribe(other, remaining::add)) {
            api.setStoredGlobalValue("total", "1").get();
            api.onPluginDisable(new PluginDisableEvent(owner));
            handle.close();
            api.setStoredGlobalValue("total", "2").get();
            assertEquals(1, owned.size());
            assertEquals(2, remaining.size());
        }
    }

    @Test
    void disabledOwnerWrongThreadAndShutdownRejectRegistration() {
        when(owner.isEnabled()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> api.subscribe(owner, change -> { }));
        when(owner.isEnabled()).thenReturn(true);
        mainThread.set(false);
        assertThrows(IllegalStateException.class, () -> api.subscribe(owner, change -> { }));
        mainThread.set(true);
        storage.shutdown();
        assertThrows(IllegalStateException.class, () -> api.subscribe(owner, change -> { }));
    }

    @Test
    void selfCloseAndClosingLaterSubscriberAreSafe() throws Exception {
        AtomicReference<PlexVariablesApi.Subscription> self = new AtomicReference<>();
        AtomicReference<PlexVariablesApi.Subscription> later = new AtomicReference<>();
        List<String> calls = new ArrayList<>();
        self.set(api.subscribe(change -> {
            calls.add("first");
            self.get().close();
            later.get().close();
        }));
        later.set(api.subscribe(change -> calls.add("later")));
        api.setStoredGlobalValue("total", "1").get();
        api.setStoredGlobalValue("total", "2").get();
        assertEquals(List.of("first"), calls);
    }

    @Test
    void shutdownClearsOwnedAndManualCallbacks() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        api.subscribe(owner, change -> callbacks.incrementAndGet());
        api.subscribe(change -> callbacks.incrementAndGet());
        api.close();
        storage.setGlobalValue("total", "1").get();
        assertEquals(0, callbacks.get());
        assertThrows(IllegalStateException.class, () -> api.subscribe(owner, change -> { }));
        assertThrows(IllegalStateException.class, () -> api.subscribe(change -> { }));
    }

    @Test
    void subscriberErrorsCannotMisreportCommittedWrite() throws Exception {
        List<PlexVariablesApi.VariableChange> changes = new ArrayList<>();
        try (var broken = api.subscribe(change -> { throw new AssertionError("consumer failure"); });
             var working = api.subscribe(changes::add)) {
            assertTrue(api.setStoredGlobalValue("total", "1").get().success());
            assertEquals(1, changes.size());
        }
    }

    @Test
    void ownerDisableDuringCallbackDoesNotBlockAndSkipsQueuedNotifications() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> calls = new ArrayList<>();
        var handle = api.subscribe(owner, change -> {
            calls.add(change.newValue().orElseThrow());
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var first = api.setStoredGlobalValue("total", "1");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = api.setStoredGlobalValue("total", "2");
            api.onPluginDisable(new PluginDisableEvent(owner));
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).success());
            assertTrue(second.get(5, TimeUnit.SECONDS).success());
            assertEquals(List.of("1"), calls);
        } finally {
            release.countDown();
            handle.close();
        }
    }

    @Test
    void registrationAfterDisableHandlerIsRejectedWhileOwnerStillReportsEnabled() {
        api.onPluginDisable(new PluginDisableEvent(owner));
        assertTrue(owner.isEnabled());
        assertThrows(IllegalArgumentException.class, () -> api.subscribe(owner, change -> { }));
    }

    @Test
    void legitimateOwnerReenableAllowsNewSubscriptions() throws Exception {
        api.onPluginDisable(new PluginDisableEvent(owner));
        api.onPluginEnable(new PluginEnableEvent(owner));
        AtomicInteger callbacks = new AtomicInteger();
        try (var subscription = api.subscribe(owner, change -> callbacks.incrementAndGet())) {
            api.setStoredGlobalValue("total", "1").get();
            assertEquals(1, callbacks.get());
        }
    }
}
