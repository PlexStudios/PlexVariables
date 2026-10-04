package dev.plex.plexvariables.listener;

import dev.plex.plexvariables.storage.StorageManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public final class StoragePlayerListener implements Listener {
    private final StorageManager storageManager;

    public StoragePlayerListener(StorageManager storageManager) {
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        storageManager.loadPlayerAsync(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        storageManager.evictPlayer(event.getPlayer().getUniqueId());
    }
}
