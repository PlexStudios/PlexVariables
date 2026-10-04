package dev.plex.plexvariables.config;

import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

public record PluginSettings(
        int maxResolutionDepth,
        int maxExpansions,
        int maxOutputLength,
        int warningCooldownSeconds,
        int listPageSize,
        String errorValue,
        boolean colorizePlaceholderOutput,
        boolean caseSensitiveConditions,
        int maxExpressionLength,
        int maxExpressionTokens,
        int maxExpressionParenthesisDepth,
        int maxStorageValueLength,
        int storageShutdownTimeoutSeconds) {
    private static final Pattern PERCENT_TOKEN = Pattern.compile("%[^%]+%");

    public PluginSettings(int maxResolutionDepth, int maxExpansions, int maxOutputLength,
                          int warningCooldownSeconds, int listPageSize, String errorValue,
                          boolean colorizePlaceholderOutput) {
        this(maxResolutionDepth, maxExpansions, maxOutputLength, warningCooldownSeconds,
                listPageSize, errorValue, colorizePlaceholderOutput, false);
    }

    public PluginSettings(int maxResolutionDepth, int maxExpansions, int maxOutputLength,
                          int warningCooldownSeconds, int listPageSize, String errorValue,
                          boolean colorizePlaceholderOutput, boolean caseSensitiveConditions) {
        this(maxResolutionDepth, maxExpansions, maxOutputLength, warningCooldownSeconds,
                listPageSize, errorValue, colorizePlaceholderOutput, caseSensitiveConditions, 4096, 512, 64);
    }

    public PluginSettings(int maxResolutionDepth, int maxExpansions, int maxOutputLength,
                          int warningCooldownSeconds, int listPageSize, String errorValue,
                          boolean colorizePlaceholderOutput, boolean caseSensitiveConditions,
                          int maxExpressionLength, int maxExpressionTokens, int maxExpressionParenthesisDepth) {
        this(maxResolutionDepth, maxExpansions, maxOutputLength, warningCooldownSeconds,
                listPageSize, errorValue, colorizePlaceholderOutput, caseSensitiveConditions,
                maxExpressionLength, maxExpressionTokens, maxExpressionParenthesisDepth, 4096, 10);
    }

    public static PluginSettings defaults() {
        return new PluginSettings(10, 1000, 65536, 60, 10, "", true, false, 4096, 512, 64, 4096, 10);
    }

    public static PluginSettings from(ConfigurationSection root) {
        if (root == null) {
            throw new IllegalArgumentException("Missing root configuration");
        }
        PluginSettings defaults = defaults();
        Object settingsValue = root.get("settings");
        if (settingsValue != null && !(settingsValue instanceof ConfigurationSection)) {
            throw new IllegalArgumentException("settings must be a section");
        }
        ConfigurationSection settings = root.getConfigurationSection("settings");
        if (settings == null) {
            return defaults;
        }
        int depth = boundedInt(settings, "max-resolution-depth", defaults.maxResolutionDepth, 1, 64);
        int expansions = boundedInt(settings, "max-expansions", defaults.maxExpansions, 1, 100000);
        int output = boundedInt(settings, "max-output-length", defaults.maxOutputLength, 1, 1048576);
        int cooldown = boundedInt(settings, "warning-cooldown-seconds", defaults.warningCooldownSeconds, 1, 3600);
        int pageSize = boundedInt(settings, "list-page-size", defaults.listPageSize, 1, 100);
        Object fallbackValue = settings.get("error-value");
        String fallback;
        if (fallbackValue == null) {
            fallback = defaults.errorValue;
        } else if (fallbackValue instanceof String text) {
            fallback = text;
        } else {
            throw new IllegalArgumentException("settings.error-value must be text");
        }
        if (fallback.length() > output || PERCENT_TOKEN.matcher(fallback).find()) {
            throw new IllegalArgumentException("settings.error-value exceeds output limit or contains a percent token");
        }
        Object colorizeValue = settings.get("colorize-placeholder-output");
        boolean colorize;
        if (colorizeValue == null) {
            colorize = defaults.colorizePlaceholderOutput;
        } else if (colorizeValue instanceof Boolean flag) {
            colorize = flag;
        } else {
            throw new IllegalArgumentException("settings.colorize-placeholder-output must be a boolean");
        }
        Object caseSensitiveValue = settings.get("conditions.case-sensitive");
        if (caseSensitiveValue == null) {
            caseSensitiveValue = settings.get("conditions-case-sensitive");
        }
        boolean caseSensitive = defaults.caseSensitiveConditions;
        if (caseSensitiveValue instanceof Boolean flag) {
            caseSensitive = flag;
        } else if (caseSensitiveValue != null) {
            throw new IllegalArgumentException("settings.conditions.case-sensitive must be a boolean");
        }

        ConfigurationSection exprSection = settings.getConfigurationSection("expressions");
        int maxExprLength = defaults.maxExpressionLength;
        int maxExprTokens = defaults.maxExpressionTokens;
        int maxExprDepth = defaults.maxExpressionParenthesisDepth;

        if (exprSection != null) {
            maxExprLength = boundedInt(exprSection, "max-length", defaults.maxExpressionLength, 1, 65536);
            maxExprTokens = boundedInt(exprSection, "max-tokens", defaults.maxExpressionTokens, 1, 10000);
            maxExprDepth = boundedInt(exprSection, "max-parenthesis-depth", defaults.maxExpressionParenthesisDepth, 1, 1000);
        }

        ConfigurationSection storageSection = settings.getConfigurationSection("storage");
        int maxStorageValLength = defaults.maxStorageValueLength;
        int storageShutdownTimeout = defaults.storageShutdownTimeoutSeconds;

        if (storageSection != null) {
            maxStorageValLength = boundedInt(storageSection, "max-value-length", defaults.maxStorageValueLength, 1, 65536);
            storageShutdownTimeout = boundedInt(storageSection, "shutdown-timeout-seconds", defaults.storageShutdownTimeoutSeconds, 1, 300);
        }

        return new PluginSettings(depth, expansions, output, cooldown, pageSize, fallback, colorize, caseSensitive,
                maxExprLength, maxExprTokens, maxExprDepth, maxStorageValLength, storageShutdownTimeout);
    }

    private static int boundedInt(ConfigurationSection settings, String key, int defaultValue, int minimum, int maximum) {
        Object value = settings.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.longValue() < minimum || number.longValue() > maximum) {
            throw new IllegalArgumentException(settings.getCurrentPath() + "." + key + " must be an integer from " + minimum + " to " + maximum);
        }
        return number.intValue();
    }
}
