package dev.plex.plexvariables.storage;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlobalVariableCache {
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public void loadAll(Map<String, String> initialData) {
        values.clear();
        if (initialData != null) {
            values.putAll(initialData);
        }
    }

    public String get(String variableId) {
        if (variableId == null) return null;
        return values.get(variableId);
    }

    public void set(String variableId, String value) {
        if (variableId == null || value == null) return;
        values.put(variableId, value);
    }

    public void remove(String variableId) {
        if (variableId == null) return;
        values.remove(variableId);
    }

    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(values);
    }
}
