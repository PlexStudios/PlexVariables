package dev.plex.plexvariables.storage;

import dev.plex.plexvariables.config.PluginSettings;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SubscriptionLifecycleTest {
    @TempDir Path directory;

    @Test
    void retainedClosedHandleReleasesOwnerListenerAndRemovalReferences() {
        var storage = new StorageManager(directory.resolve("data.db"), PluginSettings::defaults, Logger.getAnonymousLogger());
        storage.init();
        try {
            var registration = (StorageManager.Registration) storage.subscribe(mock(Plugin.class), change -> { });
            assertNotNull(registration.owner);
            assertNotNull(registration.listener);
            registration.close();
            registration.close();
            assertReleased(registration);
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void ownerCleanupReleasesReferencesEvenWhenHandleIsRetained() {
        var storage = new StorageManager(directory.resolve("data.db"), PluginSettings::defaults, Logger.getAnonymousLogger());
        storage.init();
        try {
            Plugin owner = mock(Plugin.class);
            var registration = (StorageManager.Registration) storage.subscribe(owner, change -> { });
            storage.unsubscribeOwner(owner);
            assertReleased(registration);
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void storageShutdownReleasesAllRetainedHandles() {
        var storage = new StorageManager(directory.resolve("data.db"), PluginSettings::defaults, Logger.getAnonymousLogger());
        storage.init();
        var owned = (StorageManager.Registration) storage.subscribe(mock(Plugin.class), change -> { });
        var manual = (StorageManager.Registration) storage.subscribe(change -> { });
        storage.shutdown();
        assertReleased(owned);
        assertReleased(manual);
    }

    private void assertReleased(StorageManager.Registration registration) {
        assertNull(registration.owner);
        assertNull(registration.listener);
        assertNull(registration.removal);
        assertNull(registration.logger);
    }
}
