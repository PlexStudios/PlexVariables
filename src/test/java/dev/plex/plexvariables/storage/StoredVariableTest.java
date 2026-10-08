package dev.plex.plexvariables.storage;

import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.util.MessageUtil;
import dev.plex.plexvariables.variable.VariableDefinition;
import dev.plex.plexvariables.variable.VariableManager;
import dev.plex.plexvariables.variable.VariableResolver;
import dev.plex.plexvariables.variable.VariableType;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class StoredVariableTest {
    private static final Logger LOGGER = Logger.getLogger(StoredVariableTest.class.getName());

    @TempDir
    Path tempDir;

    private Path dbPath;
    private StorageManager storageManager;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test_data.db");
        storageManager = new StorageManager(dbPath, PluginSettings::defaults, LOGGER);
        storageManager.init();
    }

    @AfterEach
    void tearDown() {
        if (storageManager != null) {
            storageManager.shutdown();
        }
    }

    @Test
    void testSQLiteInitAndSchemaVersion() {
        SQLiteStorage storage = new SQLiteStorage(tempDir.resolve("schema_test.db"), LOGGER);
        storage.init();
        storage.close();

        assertTrue(Files.exists(tempDir.resolve("schema_test.db")));
    }

    @Test
    void testYamlParsingStoredVariables() throws IOException {
        Path varsFolder = tempDir.resolve("variables");
        Files.createDirectories(varsFolder);
        Path file = varsFolder.resolve("stored.yml");
        Files.writeString(file, """
            variables:
              player_gems:
                type: stored
                scope: player
                default: "100"

              event_state:
                type: stored
                scope: global
                default: "idle"

              invalid_no_scope:
                type: stored
                default: "test"

              invalid_conflict:
                type: stored
                scope: player
                value: "hello"
            """);

        VariableManager manager = new VariableManager(LOGGER);
        VariableManager.LoadResult result = manager.load(varsFolder);

        assertEquals(2, result.variables().size());

        VariableDefinition playerGems = result.variables().get("player_gems");
        assertNotNull(playerGems);
        assertEquals(VariableType.STORED, playerGems.type());
        assertEquals(StoredVariableScope.PLAYER, playerGems.scope());
        assertEquals("100", playerGems.defaultValue());

        VariableDefinition eventState = result.variables().get("event_state");
        assertNotNull(eventState);
        assertEquals(VariableType.STORED, eventState.type());
        assertEquals(StoredVariableScope.GLOBAL, eventState.scope());
        assertEquals("idle", eventState.defaultValue());
    }

    @Test
    void testPlayerVariableCrudAndCaching() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(playerUuid);

        storageManager.loadPlayerAsync(playerUuid).get();
        assertEquals(PlayerVariableCache.CacheState.LOADED, storageManager.getPlayerCacheState(playerUuid));

        assertNull(storageManager.getPlayerValue(playerUuid, "player_gems"));

        storageManager.setPlayerValue(playerUuid, "player_gems", "250").get();
        assertEquals("250", storageManager.getPlayerValue(playerUuid, "player_gems"));

        storageManager.deletePlayerValue(playerUuid, "player_gems").get();
        assertNull(storageManager.getPlayerValue(playerUuid, "player_gems"));
    }

    @Test
    void testGlobalVariableCrudAndCaching() throws Exception {
        assertNull(storageManager.getGlobalValue("event_state"));

        storageManager.setGlobalValue("event_state", "active").get();
        assertEquals("active", storageManager.getGlobalValue("event_state"));

        storageManager.deleteGlobalValue("event_state").get();
        assertNull(storageManager.getGlobalValue("event_state"));
    }

    @Test
    void testVariableResolverWithStoredVariables() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(playerUuid);

        storageManager.loadPlayerAsync(playerUuid).get();
        storageManager.setPlayerValue(playerUuid, "gems", "500").get();
        storageManager.setGlobalValue("multiplier", "2.5").get();

        Map<String, VariableDefinition> vars = Map.of(
                "gems", VariableDefinition.ofStored("gems", StoredVariableScope.PLAYER, "0", "stored.yml"),
                "multiplier", VariableDefinition.ofStored("multiplier", StoredVariableScope.GLOBAL, "1.0", "stored.yml")
        );

        PluginState state = new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), vars, 1);
        VariableResolver resolver = new VariableResolver(() -> state, (p, text) -> text, storageManager, LOGGER);

        assertEquals("500", resolver.resolve(player, "gems"));
        assertEquals("2.5", resolver.resolve(player, "multiplier"));
        assertEquals("2.5", resolver.resolve(player, "global_multiplier"));
    }

    @Test
    void testMixedVariableChainsWithStoredVariables() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(playerUuid);

        storageManager.loadPlayerAsync(playerUuid).get();
        storageManager.setPlayerValue(playerUuid, "score", "75").get();

        Path varsFolder = tempDir.resolve("variables");
        Files.createDirectories(varsFolder);
        Path file = varsFolder.resolve("chain.yml");
        Files.writeString(file, """
            variables:
              score:
                type: stored
                scope: player
                default: "0"

              score_doubled:
                expression: "%plexvar_score% * 2"
                decimals: 0

              is_high_scorer:
                conditions:
                  - if: "%plexvar_score_doubled% >= 100"
                    then: "Yes"
                default: "No"
            """);

        VariableManager manager = new VariableManager(LOGGER);
        VariableManager.LoadResult loadResult = manager.load(varsFolder);
        PluginState state = new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), loadResult.variables(), 1);

        VariableResolver resolver = new VariableResolver(() -> state, (p, text) -> text, storageManager, LOGGER);

        assertEquals("75", resolver.resolve(player, "score"));
        assertEquals("150", resolver.resolve(player, "score_doubled"));
        assertEquals("Yes", resolver.resolve(player, "is_high_scorer"));

        storageManager.setPlayerValue(playerUuid, "score", "20").get();
        assertEquals("20", resolver.resolve(player, "score"));
        assertEquals("40", resolver.resolve(player, "score_doubled"));
        assertEquals("No", resolver.resolve(player, "is_high_scorer"));
    }

    @Test
    void testMaxValueLengthValidation() {
        assertThrows(Exception.class, () -> {
            String hugeVal = "a".repeat(5000);
            storageManager.setGlobalValue("test", hugeVal).get();
        });
    }

    @Test
    void testStaleAsyncLoadProtection() {
        PlayerVariableCache cache = new PlayerVariableCache();
        UUID uuid = UUID.randomUUID();

        long version1 = cache.markLoading(uuid);
        cache.setPlayerValue(uuid, "key", "newer_value");

        cache.setLoadedData(uuid, Map.of("key", "stale_value"), version1);

        assertEquals("newer_value", cache.getPlayerValue(uuid, "key"));
    }

    @Test
    void restartPreservesRowsSchemaAndPlaceholderResolution() throws Exception {
        UUID playerId = UUID.randomUUID();
        storageManager.setPlayerValue(playerId, "gems", "23").get();
        storageManager.setGlobalValue("multiplier", "2.5").get();
        storageManager.shutdown();
        storageManager = new StorageManager(dbPath, PluginSettings::defaults, LOGGER);
        storageManager.init();
        assertEquals("2.5", storageManager.getGlobalValue("multiplier"));
        storageManager.loadPlayerAsync(playerId).get();
        var state = new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), Map.of(
                "gems", VariableDefinition.ofStored("gems", StoredVariableScope.PLAYER, "10", "stored.yml"),
                "multiplier", VariableDefinition.ofStored("multiplier", StoredVariableScope.GLOBAL, "1", "stored.yml")), 1);
        var resolver = new VariableResolver(() -> state, (player, text) -> text, storageManager, LOGGER);
        var expansion = new dev.plex.plexvariables.placeholder.PlexVariablesExpansion(resolver, "1.0.1", "Test");
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(playerId);
        assertEquals("23", expansion.onRequest(player, "gems"));
        assertEquals("2.5", expansion.onRequest(null, "global_multiplier"));
        assertEquals("10", expansion.onRequest(null, "gems"));
        storageManager.deletePlayerValue(playerId, "gems").get();
        assertEquals("10", expansion.onRequest(player, "gems"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT value FROM plexvariables_meta WHERE key = 'schema_version'")) {
                assertTrue(rows.next());
                assertEquals("1", rows.getString(1));
            }
            for (String table : new String[]{"player_variables", "global_variables"}) {
                var columns = new ArrayList<String>();
                try (var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                    while (rows.next()) columns.add(rows.getString("name"));
                }
                assertEquals(table.equals("player_variables")
                        ? java.util.List.of("uuid", "variable_id", "value", "updated_at")
                        : java.util.List.of("variable_id", "value", "updated_at"), columns);
            }
            var tables = new ArrayList<String>();
            try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            assertEquals(java.util.List.of("global_variables", "player_variables", "plexvariables_meta"), tables);
        }
    }
}
