package dev.plex.plexvariables.placeholder;

import dev.plex.plexvariables.variable.VariableResolver;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PlexVariablesExpansion extends PlaceholderExpansion {
    private final VariableResolver resolver;
    private final String version;
    private final String author;

    public PlexVariablesExpansion(VariableResolver resolver, String version, String author) {
        this.resolver = resolver;
        this.version = version;
        this.author = author;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "plexvar";
    }

    @Override
    public @NotNull String getAuthor() {
        return author;
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return resolver.resolve(player, params);
    }
}
