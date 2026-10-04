package dev.plex.plexvariables.config;

import dev.plex.plexvariables.util.MessageUtil;
import dev.plex.plexvariables.variable.VariableManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigurationLoader {
    private final Path dataDirectory;
    private final VariableManager variableManager;

    public ConfigurationLoader(Path dataDirectory, Logger logger) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.variableManager = new VariableManager(Objects.requireNonNull(logger, "logger"));
    }

    public PluginState load() throws IOException {
        YamlConfiguration config = read(dataDirectory.resolve("config.yml"));
        YamlConfiguration messageConfig = read(dataDirectory.resolve("messages.yml"));
        final PluginSettings settings;
        final MessageUtil messages;
        try {
            settings = PluginSettings.from(config);
            messages = MessageUtil.from(messageConfig);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid configuration: " + exception.getMessage(), exception);
        }
        VariableManager.LoadResult variables = variableManager.load(dataDirectory.resolve("variables"), settings);
        return new PluginState(settings, messages, variables.variables(), variables.filesLoaded());
    }

    private static YamlConfiguration read(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Malformed configuration file " + file.getFileName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }
}
