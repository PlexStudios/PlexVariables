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
        Thread.sleep(100);
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Set stored variable global_event for global to 'active'")));

        command.onCommand(sender(messages), null, "pv", new String[]{"get", "global_event"});
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("active")));

        command.onCommand(sender(messages), null, "pv", new String[]{"set", "gems", "Alex", "100"});
        Thread.sleep(100);
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Set stored variable gems for Alex to '100'")));

        command.onCommand(sender(messages), null, "pv", new String[]{"add", "gems", "Alex", "50"});
        Thread.sleep(100);
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Added 50 to stored variable gems for Alex")));

        command.onCommand(sender(messages), null, "pv", new String[]{"test", "Alex", "gems"});
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Scope:      PLAYER")));
        assertTrue(messages.stream().anyMatch(msg -> msg.contains("Raw Stored: 150")));

        storageManager.shutdown();
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
