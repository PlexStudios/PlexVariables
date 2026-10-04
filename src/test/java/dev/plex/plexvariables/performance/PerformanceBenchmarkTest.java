package dev.plex.plexvariables.performance;

import dev.plex.plexvariables.condition.ConditionParser;
import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.expression.CompiledExpression;
import dev.plex.plexvariables.expression.ExpressionFormatting;
import dev.plex.plexvariables.expression.ExpressionParser;
import dev.plex.plexvariables.storage.StorageManager;
import dev.plex.plexvariables.storage.StoredVariableScope;
import dev.plex.plexvariables.util.MessageUtil;
import dev.plex.plexvariables.variable.VariableDefinition;
import dev.plex.plexvariables.variable.VariableResolver;
import dev.plex.plexvariables.variable.VariableType;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class PerformanceBenchmarkTest {
    private static final Logger LOGGER = Logger.getLogger(PerformanceBenchmarkTest.class.getName());

    @TempDir
    Path tempDir;

    private StorageManager storageManager;

    @BeforeEach
    void setUp() {
        Path dbFile = tempDir.resolve("bench.db");
        storageManager = new StorageManager(dbFile, PluginSettings::defaults, LOGGER);
        storageManager.init();
    }

    @AfterEach
    void tearDown() {
        if (storageManager != null) {
            storageManager.shutdown();
        }
    }

    @Test
    void benchmarkPlaceholderResolutionHotPath() throws Exception {
        UUID uuid = UUID.randomUUID();
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);

        storageManager.loadPlayerAsync(uuid).get();
        storageManager.setPlayerValue(uuid, "player_points", "500").get();
        storageManager.setGlobalValue("server_multiplier", "2").get();

        VariableDefinition staticVar = new VariableDefinition("server_name", "PlexNetwork", "general.yml", VariableType.STATIC);
        VariableDefinition condVar = VariableDefinition.ofConditional("status_rank",
                List.of(ConditionParser.parse("%plexvar_player_points% >= 100", "VIP")), "Default", "conditions.yml");
        CompiledExpression compiled = ExpressionParser.parse("%plexvar_player_points% * %plexvar_server_multiplier%", 4096, 512, 64);
        VariableDefinition exprVar = VariableDefinition.ofExpression("total_score", compiled, ExpressionFormatting.DEFAULT, null, "expressions.yml");
        VariableDefinition storedVar = VariableDefinition.ofStored("player_points", StoredVariableScope.PLAYER, "0", "stored.yml");
        VariableDefinition globalStored = VariableDefinition.ofStored("server_multiplier", StoredVariableScope.GLOBAL, "1", "stored.yml");

        Map<String, VariableDefinition> vars = Map.of(
                "server_name", staticVar,
                "status_rank", condVar,
                "total_score", exprVar,
                "player_points", storedVar,
                "server_multiplier", globalStored
        );

        PluginState state = new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), vars, 5);
        VariableResolver resolver = new VariableResolver(() -> state, (p, text) -> text, storageManager, LOGGER);

        for (int i = 0; i < 1_000; i++) {
            resolver.resolve(player, "server_name");
            resolver.resolve(player, "status_rank");
            resolver.resolve(player, "total_score");
            resolver.resolve(player, "player_points");
        }

        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            resolver.resolve(player, "server_name");
            resolver.resolve(player, "status_rank");
            resolver.resolve(player, "total_score");
            resolver.resolve(player, "player_points");
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        LOGGER.info("Performance Smoke Test: 100,000 4-variable resolution cycles executed in " + durationMs + "ms (" + String.format("%.4f", (double) durationMs / iterations) + "ms/cycle).");

        assertEquals("PlexNetwork", resolver.resolve(player, "server_name"));
        assertEquals("VIP", resolver.resolve(player, "status_rank"));
        assertEquals("1000", resolver.resolve(player, "total_score"));
        assertEquals("500", resolver.resolve(player, "player_points"));
    }
}
