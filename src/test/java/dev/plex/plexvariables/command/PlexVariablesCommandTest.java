package dev.plex.plexvariables.command;

import dev.plex.plexvariables.condition.CompiledCondition;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import dev.plex.plexvariables.api.PlexVariablesApi;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlexVariablesCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesOnlinePlayerBeforeAnyCachedLookupAndKeepsTextSpaces() {
        Server server = mock(Server.class);
        Player online = mock(Player.class);
        when(server.getPlayerExact("Alex")).thenReturn(online);
        var playerSeen = new AtomicReference<OfflinePlayer>();
        var state = state(Map.of());
        var resolver = new VariableResolver(() -> state, (player, text) -> {
            playerSeen.set(player);
            return "Alex";
        }, Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();
        var command = new PlexVariablesCommand(() -> state, resolver, null, server, Logger.getAnonymousLogger());
        command.onCommand(sender(messages), null, "pv", new String[]{"parse", "Alex", "Hello", "%player_name%"});
        assertSame(online, playerSeen.get());
        assertTrue(messages.contains("Result: Hello Alex"));
        verify(server, never()).getOfflinePlayerIfCached(anyString());
        verify(server, never()).getOfflinePlayer(anyString());
    }

    @Test
    void missingPlayerUsesOnlyCacheAndNullContextNeedsNoLookup() {
        Server server = mock(Server.class);
        var state = state(Map.of());
        var resolver = new VariableResolver(() -> state, (player, token) -> token, Logger.getAnonymousLogger());
        var command = new PlexVariablesCommand(() -> state, resolver, null, server, Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();
        command.onCommand(sender(messages), null, "pv", new String[]{"parse", "Unknown", "%player_name%"});
        verify(server).getOfflinePlayerIfCached("Unknown");
        verify(server, never()).getOfflinePlayer(anyString());
        assertTrue(messages.getFirst().contains("not online or cached"));
        clearInvocations(server);
        command.onCommand(sender(messages), null, "pv", new String[]{"parse", "--null", "literal"});
        verifyNoInteractions(server);
        assertTrue(messages.contains("Result: literal"));
    }

    @Test
    void deniesCommandAndCompletionWithoutSubcommandPermission() {
        var state = state(Map.of());
        var resolver = new VariableResolver(() -> state, (player, token) -> token, Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        var command = new PlexVariablesCommand(() -> state, resolver, null, server, Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();
        CommandSender sender = sender(messages);
        when(sender.hasPermission("plexvariables.parse")).thenReturn(false);
        command.onCommand(sender, null, "pv", new String[]{"parse", "Alex", "value"});
        assertTrue(messages.getFirst().contains("do not have permission"));
        assertFalse(command.onTabComplete(sender, null, "pv", new String[]{""}).contains("parse"));
        assertTrue(command.onTabComplete(sender, null, "pv", new String[]{"parse", ""}).isEmpty());
        verifyNoInteractions(server);
    }

    @Test
    void listsSortedIdsWithSourceAndRejectsInvalidPages() {
        var state = state(Map.of("z", variable("z"), "a", variable("a")));
        var resolver = new VariableResolver(() -> state, (player, token) -> token, Logger.getAnonymousLogger());
        var command = new PlexVariablesCommand(() -> state, resolver, null, mock(Server.class), Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();
        command.onCommand(sender(messages), null, "pv", new String[]{"list"});
        assertEquals("a [STATIC] - general.yml", messages.get(1));
        assertEquals("z [STATIC] - general.yml", messages.get(2));
        command.onCommand(sender(messages), null, "pv", new String[]{"list", "2147483648"});
        assertTrue(messages.getLast().contains("Choose a page"));
    }

    @Test
    void testSubcommandExecutesTraceAndDisplaysSteps() {
        VariableDefinition conditionalVar = VariableDefinition.ofConditional("health",
                List.of(ConditionParser.parse("10 > 5", "high")), "low", "conditions.yml");
        var state = state(Map.of("health", conditionalVar));
        var resolver = new VariableResolver(() -> state, (player, token) -> token, Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        var command = new PlexVariablesCommand(() -> state, resolver, null, server, Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();

        command.onCommand(sender(messages), null, "pv", new String[]{"test", "--null", "health"});
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Variable Test » health [CONDITIONAL]")));
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Condition #1:")));
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Result: high")));
    }

    @Test
    void testSubcommandExecutesExpressionTrace() {
        CompiledExpression compiled = ExpressionParser.parse("10 + 5", 4096, 512, 64);
        VariableDefinition exprVar = VariableDefinition.ofExpression("math", compiled, ExpressionFormatting.DEFAULT, null, "expressions.yml");
        var state = state(Map.of("math", exprVar));
        var resolver = new VariableResolver(() -> state, (player, token) -> token, Logger.getAnonymousLogger());
        Server server = mock(Server.class);
        var command = new PlexVariablesCommand(() -> state, resolver, null, server, Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();

        command.onCommand(sender(messages), null, "pv", new String[]{"test", "--null", "math"});
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Variable Test » math [EXPRESSION]")));
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Expression: 10 + 5")));
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Result: 15")));
    }

    @Test
    void testStoredVariableCommands() throws Exception {
        Path dbFile = tempDir.resolve("cmd_test.db");
        StorageManager storageManager = new StorageManager(dbFile, PluginSettings::defaults, Logger.getAnonymousLogger());
        storageManager.init();

        VariableDefinition globalStored = VariableDefinition.ofStored("global_event", StoredVariableScope.GLOBAL, "inactive", "stored.yml");
        VariableDefinition playerStored = VariableDefinition.ofStored("gems", StoredVariableScope.PLAYER, "0", "stored.yml");

        var state = state(Map.of("global_event", globalStored, "gems", playerStored));
        Server server = mock(Server.class);
        Player mockPlayer = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(mockPlayer.getUniqueId()).thenReturn(uuid);
        when(mockPlayer.getName()).thenReturn("Alex");
        when(server.getPlayerExact("Alex")).thenReturn(mockPlayer);

        storageManager.loadPlayerAsync(uuid).get();

        var resolver = new VariableResolver(() -> state, (player, token) -> token, storageManager, Logger.getAnonymousLogger());
        var command = new PlexVariablesCommand(() -> state, resolver, null, storageManager, server, Logger.getAnonymousLogger());
        var messages = new ArrayList<String>();

        command.onCommand(sender(messages), null, "pv", new String[]{"set", "global_event", "global", "active"});
        storageManager.readStored("global_event", PlexVariablesApi.Scope.GLOBAL, null).get(5, TimeUnit.SECONDS);
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Set stored variable global_event for global to 'active'")));

        command.onCommand(sender(messages), null, "pv", new String[]{"get", "global_event"});
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("active")));

        command.onCommand(sender(messages), null, "pv", new String[]{"set", "gems", "Alex", "100"});
        storageManager.readStored("gems", PlexVariablesApi.Scope.PLAYER, uuid).get(5, TimeUnit.SECONDS);
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Set stored variable gems for Alex to '100'")));

        command.onCommand(sender(messages), null, "pv", new String[]{"add", "gems", "Alex", "50"});
        storageManager.readStored("gems", PlexVariablesApi.Scope.PLAYER, uuid).get(5, TimeUnit.SECONDS);
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Added 50 to stored variable gems for Alex")));

        command.onCommand(sender(messages), null, "pv", new String[]{"test", "Alex", "gems"});
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Scope:      PLAYER")));
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Raw Stored: 150")));

        storageManager.shutdown();
    }

    @Test
    void storedCommandsPreserveBothScopesDefaultsRemoveAndNotifications() throws Exception {
        var storage = new StorageManager(tempDir.resolve("regression.db"), PluginSettings::defaults, Logger.getAnonymousLogger());
        storage.init();
        try {
            var state = state(Map.of(
                    "player_value", VariableDefinition.ofStored("player_value", StoredVariableScope.PLAYER, "10", "test.yml"),
                    "global_value", VariableDefinition.ofStored("global_value", StoredVariableScope.GLOBAL, "10", "test.yml"),
                    "unset", VariableDefinition.ofStored("unset", StoredVariableScope.GLOBAL, null, "test.yml")));
            Server server = mock(Server.class);
            Player player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Alex");
            when(server.getPlayerExact("Alex")).thenReturn(player);
            storage.loadPlayerAsync(id).get(5, TimeUnit.SECONDS);
            var resolver = new VariableResolver(() -> state, (target, text) -> text, storage, Logger.getAnonymousLogger());
            var api = new dev.plex.plexvariables.implementation.DefaultPlexVariablesApi(() -> state, resolver, storage, () -> true);
            var command = new PlexVariablesCommand(() -> state, resolver, null, storage, server, Logger.getAnonymousLogger());
            var messages = new ArrayList<String>();
            var sender = sender(messages);
            var changes = new ArrayList<PlexVariablesApi.VariableChange>();
            try (var subscription = api.subscribe(changes::add)) {
                for (String variable : List.of("player_value", "global_value")) {
                    String target = variable.equals("player_value") ? "Alex" : "global";
                    var scope = target.equals("Alex") ? PlexVariablesApi.Scope.PLAYER : PlexVariablesApi.Scope.GLOBAL;
                    UUID uuid = scope == PlexVariablesApi.Scope.PLAYER ? id : null;
                    command.onCommand(sender, null, "pvar", new String[]{"set", variable, target, "10"});
                    assertEquals("10", storage.readStored(variable, scope, uuid).get(5, TimeUnit.SECONDS).orElseThrow());
                    assertTrue(changes.isEmpty());
                    command.onCommand(sender, null, "plexvar", new String[]{"reset", variable, target});
                    assertTrue(storage.readStored(variable, scope, uuid).get(5, TimeUnit.SECONDS).isEmpty());
                    assertTrue(changes.isEmpty());
                }
                for (String variable : List.of("player_value", "global_value")) {
                    String target = variable.equals("player_value") ? "Alex" : "global";
                    var scope = target.equals("Alex") ? PlexVariablesApi.Scope.PLAYER : PlexVariablesApi.Scope.GLOBAL;
                    UUID uuid = scope == PlexVariablesApi.Scope.PLAYER ? id : null;
                    command.onCommand(sender, null, "pv", new String[]{"add", variable, target, "2.5"});
                    assertEquals("12.5", storage.readStored(variable, scope, uuid).get(5, TimeUnit.SECONDS).orElseThrow());
                    command.onCommand(sender, null, "pv", new String[]{"get", variable, target});
                    assertTrue(messages.stream().anyMatch(message -> message.contains("12.5")));
                    command.onCommand(sender, null, "pv", new String[]{"remove", variable, target});
                    assertTrue(storage.readStored(variable, scope, uuid).get(5, TimeUnit.SECONDS).isEmpty());
                }
                assertEquals(4, changes.size());
                assertEquals(PlexVariablesApi.Scope.PLAYER, changes.getFirst().scope());
                assertEquals(java.util.Optional.of(id), changes.getFirst().playerId());
                assertEquals(PlexVariablesApi.Status.MISSING_VALUE, api.addStoredGlobalValue("unset", java.math.BigDecimal.ONE).get().status());
                command.onCommand(sender, null, "pv", new String[]{"add", "unset", "global", "1"});
                assertEquals("1", api.getStoredGlobalValue("unset").get(5, TimeUnit.SECONDS).orElseThrow());
                int count = changes.size();
                when(sender.hasPermission("plexvariables.set")).thenReturn(false);
                command.onCommand(sender, null, "pv", new String[]{"set", "unset", "global", "9"});
                assertEquals("1", api.getStoredGlobalValue("unset").get(5, TimeUnit.SECONDS).orElseThrow());
                command.onCommand(sender, null, "pv", new String[]{"add", "unset", "global", "invalid"});
                assertEquals("1", api.getStoredGlobalValue("unset").get(5, TimeUnit.SECONDS).orElseThrow());
                assertEquals(count, changes.size());
                assertTrue(messages.stream().anyMatch(message -> message.contains("not a valid number")));
            }
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void nonnumericStoredAndDefaultValuesRetainConfiguredCommandMessage() throws Exception {
        var storage = new StorageManager(tempDir.resolve("invalid.db"), PluginSettings::defaults, Logger.getAnonymousLogger());
        storage.init();
        try {
            var state = state(Map.of(
                    "player_value", VariableDefinition.ofStored("player_value", StoredVariableScope.PLAYER, "invalid", "test.yml"),
                    "global_value", VariableDefinition.ofStored("global_value", StoredVariableScope.GLOBAL, "invalid", "test.yml")));
            Server server = mock(Server.class);
            Player player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Alex");
            when(server.getPlayerExact("Alex")).thenReturn(player);
            var resolver = new VariableResolver(() -> state, (target, text) -> text, storage, Logger.getAnonymousLogger());
            var command = new PlexVariablesCommand(() -> state, resolver, null, storage, server, Logger.getAnonymousLogger());
            var messages = new ArrayList<String>();
            var sender = sender(messages);
            for (String variable : List.of("player_value", "global_value")) {
                String target = variable.equals("player_value") ? "Alex" : "global";
                var scope = target.equals("Alex") ? PlexVariablesApi.Scope.PLAYER : PlexVariablesApi.Scope.GLOBAL;
                UUID uuid = scope == PlexVariablesApi.Scope.PLAYER ? id : null;
                for (boolean stored : new boolean[]{false, true}) {
                    if (stored) {
                        if (uuid == null) storage.setGlobalValue(variable, "garbage").get();
                        else storage.setPlayerValue(uuid, variable, "garbage").get();
                    }
                    messages.clear();
                    command.onCommand(sender, null, "pv", new String[]{"add", variable, target, "1"});
                    storage.readStored(variable, scope, uuid).get(5, TimeUnit.SECONDS);
                    assertTrue(messages.stream().anyMatch(message -> message.contains("Stored value or added amount is not a valid number")));
                    assertFalse(messages.stream().anyMatch(message -> message.contains("Storage operation failed")));
                }
            }
        } finally {
            storage.shutdown();
        }
    }

    private static CommandSender sender(ArrayList<String> output) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> {
            output.add(PlainTextComponentSerializer.plainText().serialize(invocation.getArgument(0, Component.class)));
            return null;
        }).when(sender).sendMessage(any(Component.class));
        return sender;
    }

    private static PluginState state(Map<String, VariableDefinition> variables) {
        return new PluginState(PluginSettings.defaults(), MessageUtil.from(new YamlConfiguration()), variables, 1);
    }

    private static VariableDefinition variable(String id) {
        return new VariableDefinition(id, "literal", "general.yml", VariableType.STATIC);
    }
}
