package dev.plex.plexvariables.storage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerVariableCache {

    public enum CacheState {
        UNLOADED,
        LOADING,
        LOADED
    }

    public static final class CacheEntry {
        private volatile CacheState state;
        private final Map<String, String> variables;
        private volatile long version;

        public CacheEntry(CacheState state, Map<String, String> variables, long version) {
            this.state = state;
            this.variables = new ConcurrentHashMap<>(variables);
            this.version = version;
        }

        public CacheState state() {
            return state;
        }

        public Map<String, String> variables() {
            return variables;
        }

        public long version() {
            return version;
        }
    }

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public CacheState getCacheState(UUID uuid) {
        if (uuid == null) return CacheState.UNLOADED;
        CacheEntry entry = cache.get(uuid);
        return entry == null ? CacheState.UNLOADED : entry.state();
    }

    public String getPlayerValue(UUID uuid, String variableId) {
        if (uuid == null || variableId == null) return null;
        CacheEntry entry = cache.get(uuid);
        if (entry == null || entry.state() != CacheState.LOADED) {
            return null;
        }
        return entry.variables().get(variableId);
    }

    public synchronized long markLoading(UUID uuid) {
        if (uuid == null) return -1;
        CacheEntry existing = cache.get(uuid);
        long newVersion = existing == null ? 1 : existing.version() + 1;
        if (existing == null) {
            cache.put(uuid, new CacheEntry(CacheState.LOADING, Map.of(), newVersion));
        } else {
            existing.state = CacheState.LOADING;
            existing.version = newVersion;
        }
        return newVersion;
    }

    public synchronized void setLoadedData(UUID uuid, Map<String, String> data, long loadVersion) {
        if (uuid == null) return;
        CacheEntry existing = cache.get(uuid);
        if (existing != null && existing.version() > loadVersion) {
            return;
        }
        Map<String, String> map = data == null ? Map.of() : data;
        if (existing == null) {
            cache.put(uuid, new CacheEntry(CacheState.LOADED, map, loadVersion));
        } else {
            existing.variables.clear();
            existing.variables.putAll(map);
            existing.state = CacheState.LOADED;
            existing.version = loadVersion;
        }
    }

    public synchronized void setPlayerValue(UUID uuid, String variableId, String value) {
        if (uuid == null || variableId == null || value == null) return;
        CacheEntry existing = cache.get(uuid);
        if (existing == null) {
            Map<String, String> map = new ConcurrentHashMap<>();
            map.put(variableId, value);
            cache.put(uuid, new CacheEntry(CacheState.LOADED, map, 1));
        } else {
            existing.variables.put(variableId, value);
            existing.state = CacheState.LOADED;
            existing.version++;
        }
    }

    public synchronized void removePlayerValue(UUID uuid, String variableId) {
        if (uuid == null || variableId == null) return;
        CacheEntry existing = cache.get(uuid);
        if (existing != null) {
            existing.variables.remove(variableId);
            existing.version++;
        }
    }

    public void evictPlayer(UUID uuid) {
        if (uuid == null) return;
        cache.remove(uuid);
    }

    public CacheEntry getEntry(UUID uuid) {
        if (uuid == null) return null;
        return cache.get(uuid);
    }
}
