package dev.plex.plexvariables.variable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ResolutionWarnings {
    private static final int MAX_KEYS = 256;
    private static final int MAX_WARNINGS_PER_WINDOW = 32;
    private static final int MAX_FIELD_LENGTH = 120;

    private final Logger logger;
    private final Map<String, Long> lastWarnings = new LinkedHashMap<>();
    private long windowStart;
    private int warningsInWindow;

    ResolutionWarnings(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void warn(String variable, String source, String cause, int cooldownSeconds) {
        String safeVariable = bounded(variable);
        String safeSource = bounded(source);
        String safeCause = bounded(cause);
        String key = safeVariable + '\u0000' + safeSource + '\u0000' + safeCause;
        long now = System.nanoTime();
        long cooldown = TimeUnit.SECONDS.toNanos(cooldownSeconds);
        synchronized (lastWarnings) {
            if (windowStart == 0 || now - windowStart >= cooldown) {
                windowStart = now;
                warningsInWindow = 0;
            }
            Long previous = lastWarnings.get(key);
            if (previous != null && now - previous < cooldown) {
                return;
            }
            if (warningsInWindow >= MAX_WARNINGS_PER_WINDOW) return;
            if (previous == null && lastWarnings.size() >= MAX_KEYS) {
                String eldest = lastWarnings.keySet().iterator().next();
                lastWarnings.remove(eldest);
            }
            lastWarnings.put(key, now);
            warningsInWindow++;
        }
        logger.log(Level.WARNING, "Variable '" + safeVariable + "' in '" + safeSource + "': " + safeCause);
    }

    void clear() {
        synchronized (lastWarnings) {
            lastWarnings.clear();
            windowStart = 0;
            warningsInWindow = 0;
        }
    }

    private static String bounded(String value) {
        String text = value == null ? "<unknown>" : value.replace('\r', ' ').replace('\n', ' ');
        return text.length() <= MAX_FIELD_LENGTH ? text : text.substring(0, MAX_FIELD_LENGTH) + "…";
    }
}
