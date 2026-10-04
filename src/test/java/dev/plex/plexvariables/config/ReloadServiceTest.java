package dev.plex.plexvariables.config;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReloadServiceTest {
    @TempDir Path directory;

    @Test
    void publishesOnlyWhenMainThreadCallbackRunsAndRejectsOverlappingReloads() throws Exception {
        var fixture = fixture();
        PluginState before = fixture.state.get();
        Files.writeString(directory.resolve("variables/test.yml"), "variables:\n  new_value:\n    value: new\n");
        try (var service = fixture.service) {
            assertTrue(service.reload(fixture.sender));
            Runnable publish = fixture.callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(publish);
            assertSame(before, fixture.state.get());
            assertFalse(service.reload(fixture.sender));
            publish.run();
            assertEquals("new", fixture.state.get().variables().get("new_value").value());
            assertFalse(fixture.state.get().variables().containsKey("old_value"));
            assertEquals(1, fixture.afterReload.get());
            assertTrue(service.reload(fixture.sender));
        }
    }

    @Test
    void failedConfigurationKeepsWholePreviousSnapshot() throws Exception {
        var fixture = fixture();
        PluginState before = fixture.state.get();
        Files.writeString(directory.resolve("config.yml"), "settings: [broken");
        try (var service = fixture.service) {
            assertTrue(service.reload(fixture.sender));
            Runnable publish = fixture.callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(publish);
            publish.run();
            assertSame(before, fixture.state.get());
            assertEquals(0, fixture.afterReload.get());
        }
    }

    @Test
    void disablePreventsQueuedPublication() throws Exception {
        var fixture = fixture();
        PluginState before = fixture.state.get();
        try (var service = fixture.service) {
            assertTrue(service.reload(fixture.sender));
            Runnable publish = fixture.callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(publish);
            service.close();
            publish.run();
            assertSame(before, fixture.state.get());
            assertEquals(0, fixture.afterReload.get());
            assertFalse(service.reload(fixture.sender));
        }
    }

    private Fixture fixture() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "settings: {}\n");
        Files.writeString(directory.resolve("messages.yml"), "messages: {}\n");
        Files.createDirectory(directory.resolve("variables"));
        Files.writeString(directory.resolve("variables/test.yml"), "variables:\n  old_value:\n    value: old\n");
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        var loader = new ConfigurationLoader(directory, logger);
        var state = new AtomicReference<>(loader.load());
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var afterReload = new AtomicInteger();
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return null;
        });
        return new Fixture(new ReloadService(plugin, loader, state, afterReload::incrementAndGet),
                state, callbacks, afterReload, mock(CommandSender.class));
    }

    private record Fixture(ReloadService service, AtomicReference<PluginState> state,
                           LinkedBlockingQueue<Runnable> callbacks, AtomicInteger afterReload, CommandSender sender) {
    }
}
