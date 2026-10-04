package dev.plex.plexvariables.config;

import dev.plex.plexvariables.util.MessageUtil;
import dev.plex.plexvariables.variable.VariableDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PluginState(
        PluginSettings settings,
        MessageUtil messages,
        Map<String, VariableDefinition> variables,
        int filesLoaded) {
    public PluginState {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(messages, "messages");
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(variables, "variables")));
        if (filesLoaded < 0) {
            throw new IllegalArgumentException("filesLoaded must be nonnegative");
        }
    }
}
