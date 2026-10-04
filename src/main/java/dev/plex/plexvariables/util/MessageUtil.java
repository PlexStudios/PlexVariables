package dev.plex.plexvariables.util;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MessageUtil {
    private static final Pattern PARAMETER = Pattern.compile("%([a-zA-Z0-9_-]+)%");
    private static final Defaults DEFAULTS = loadDefaults();

    private final String prefix;
    private final Map<String, List<String>> templates;

    private MessageUtil(String prefix, Map<String, List<String>> templates) {
        this.prefix = prefix;
        this.templates = Map.copyOf(templates);
    }

    public static MessageUtil from(ConfigurationSection root) {
        Object rawPrefix = root.get("prefix", DEFAULTS.prefix());
        if (!(rawPrefix instanceof String prefix)) {
            throw new IllegalArgumentException("messages.yml: prefix must be text");
        }
        Map<String, List<String>> templates = new LinkedHashMap<>(DEFAULTS.templates());
        if (root.contains("messages")) {
            ConfigurationSection section = root.getConfigurationSection("messages");
            if (section == null) {
                throw new IllegalArgumentException("messages.yml: messages must be a mapping");
            }
            for (String key : section.getKeys(false)) {
                templates.put(key, readLines(key, section.get(key)));
            }
        }
        return new MessageUtil(prefix, templates);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> replacements) {
        render(key, replacements).forEach(sender::sendMessage);
    }

    public List<Component> render(String key, Map<String, String> replacements) {
        List<String> lines = templates.getOrDefault(key, List.of());
        List<Component> rendered = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.isEmpty()) {
                var matcher = PARAMETER.matcher(line);
                StringBuilder result = new StringBuilder(line.length());
                while (matcher.find()) {
                    String parameter = matcher.group(1);
                    String value = parameter.equals("prefix") ? prefix : replacements.get(parameter);
                    matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                            value == null ? matcher.group() : value));
                }
                matcher.appendTail(result);
                rendered.add(ColorUtil.component(result.toString()));
            }
        }
        return List.copyOf(rendered);
    }

    private static List<String> readLines(String key, Object value) {
        if (value instanceof String text) {
            return List.of(text.split("\\R", -1));
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).flatMap(text -> text.lines()).toList();
        }
        throw new IllegalArgumentException("messages.yml: messages." + key + " must be text or a list of text");
    }

    private static Defaults loadDefaults() {
        var yaml = new YamlConfiguration();
        try (var stream = Objects.requireNonNull(MessageUtil.class.getResourceAsStream("/messages.yml"),
                "Bundled messages.yml is missing");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Cannot load bundled messages.yml", exception);
        }
        var section = Objects.requireNonNull(yaml.getConfigurationSection("messages"));
        Map<String, List<String>> templates = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            templates.put(key, readLines(key, section.get(key)));
        }
        return new Defaults(yaml.getString("prefix", ""), Map.copyOf(templates));
    }

    private record Defaults(String prefix, Map<String, List<String>> templates) {
    }
}
