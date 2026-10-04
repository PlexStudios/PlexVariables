package dev.plex.plexvariables.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationLoaderTest {
    @TempDir Path dataDirectory;

    @Test
    void settingsUseDefaultsAndValidateBoundsAndFallback() {
        YamlConfiguration root = new YamlConfiguration();
        assertEquals(PluginSettings.defaults(), PluginSettings.from(root));

        root.set("settings.max-resolution-depth", 64);
        root.set("settings.max-expansions", 1);
        root.set("settings.max-output-length", 3);
        root.set("settings.error-value", "safe");
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(root));

        root.set("settings.max-output-length", 4);
        assertEquals("safe", PluginSettings.from(root).errorValue());
        root.set("settings.error-value", "%player_name%");
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(root));
        root.set("settings.error-value", "ok");
        root.set("settings.list-page-size", 0);
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.from(root));
    }

    @Test
    void successfulReloadReturnsFreshSnapshotWithoutDeletedDefinitions() throws IOException {
        seed("settings:\n  max-resolution-depth: 5\n", "prefix: '&7Test'\n");
        Files.writeString(dataDirectory.resolve("variables/first.yml"), "variables:\n  one: first\n");
        ConfigurationLoader loader = new ConfigurationLoader(dataDirectory, Logger.getLogger("config-test"));
        PluginState first = loader.load();
        assertEquals(5, first.settings().maxResolutionDepth());
        assertEquals("first", first.variables().get("one").value());

        Files.delete(dataDirectory.resolve("variables/first.yml"));
        Files.writeString(dataDirectory.resolve("variables/second.yml"), "variables:\n  two: second\n");
        PluginState second = loader.load();

        assertTrue(first.variables().containsKey("one"));
        assertFalse(second.variables().containsKey("one"));
        assertEquals("second", second.variables().get("two").value());
        assertEquals(1, second.filesLoaded());
        assertThrows(UnsupportedOperationException.class,
                () -> second.variables().put("one", first.variables().get("one")));
    }

    @Test
    void invalidMainConfigAndMessagesAreFatal() throws IOException {
        seed("settings:\n  list-page-size: 101\n", "prefix: '&7Test'\n");
        ConfigurationLoader loader = new ConfigurationLoader(dataDirectory, Logger.getLogger("config-test"));
        assertThrows(IOException.class, loader::load);

        Files.writeString(dataDirectory.resolve("config.yml"), "settings: {}\n");
        Files.writeString(dataDirectory.resolve("messages.yml"), "prefix: [invalid, list]\n");
        assertThrows(IOException.class, loader::load);
    }

    private void seed(String config, String messages) throws IOException {
        Files.createDirectories(dataDirectory.resolve("variables"));
        Files.writeString(dataDirectory.resolve("config.yml"), config);
        Files.writeString(dataDirectory.resolve("messages.yml"), messages);
    }
}
