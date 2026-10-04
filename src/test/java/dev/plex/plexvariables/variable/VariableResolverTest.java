package dev.plex.plexvariables.variable;

import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.util.MessageUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableResolverTest {
    private static final String ERROR = "[error]";

    @Test
    void literalNeedsNoExternalParsing() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of("hello", variable("hello", "literal")),
                (player, token) -> { throw new AssertionError("literal called parser"); });
        assertEquals("literal", resolver.resolve(null, "HELLO"));
    }

    @Test
    void nestedOwnTokensUseCaseInsensitiveIdsAndDoNotReparseExpandedText() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(
                "greeting", variable("greeting", "Hi %PlExVaR_NaMe%!"),
                "name", variable("name", "Ada %unknown%")),
                (player, token) -> token.equals("%unknown%") ? "X" : token);
        assertEquals("Hi Ada X!", resolver.resolve(null, "GREETING"));
    }

    @Test
    void unknownOwnIdsAndOrdinaryPercentTextRemainIntact() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(), (player, token) -> token);
        assertNull(resolver.resolve(null, "missing"));
        assertEquals("50% off %plexvar_missing% and %other_x%", resolver.parse(null,
                "50% off %plexvar_missing% and %other_x%"));
    }

    @Test
    void externalParametersMayContainSpacesAndPunctuation() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(),
                (player, token) -> token.equals("%external_hello world +?%") ? "matched" : token);
        assertEquals("matched", resolver.parse(null, "%external_hello world +?%"));
    }

    @Test
    void playerIsPassedThroughAndResultsAreNotCached() {
        OfflinePlayer player = (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> null);
        AtomicInteger calls = new AtomicInteger();
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of("name", variable("name", "%external_name%")),
                (actual, token) -> {
                    assertSame(player, actual);
                    return "name-" + calls.incrementAndGet();
                });
        assertEquals("name-1", resolver.resolve(player, "name"));
        assertEquals("name-2", resolver.resolve(player, "name"));
    }

    @Test
    void nullPlayerIsPassedToExternalParser() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of("name", variable("name", "%external_name%")),
                (player, token) -> player == null ? "console" : "player");
        assertEquals("console", resolver.resolve(null, "name"));
    }

    @Test
    void directAndIndirectCyclesReturnConfiguredSafeText() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(
                "a", variable("a", "%plexvar_b%"), "b", variable("b", "%plexvar_a%")),
                (player, token) -> token);
        assertEquals(ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void selfReferencingCycleReturnsSafeText() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(
                "loop", variable("loop", "%plexvar_loop%")),
                (player, token) -> token);
        assertEquals(ERROR, resolver.resolve(null, "loop"));
    }

    @Test
    void threeWayIndirectCycleReturnsSafeText() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(
                "a", variable("a", "%plexvar_b%"),
                "b", variable("b", "%plexvar_c%"),
                "c", variable("c", "%plexvar_a%")),
                (player, token) -> token);
        assertEquals(ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void cycleWarningIncludesResolutionPath() {
        Logger logger = logger();
        AtomicReference<String> message = new AtomicReference<>();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { message.set(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        VariableResolver resolver = new VariableResolver(() -> state(settings(10, 100, 100), Map.of(
                "x", variable("x", "%plexvar_y%"),
                "y", variable("y", "%plexvar_x%"))), (player, token) -> token, logger);
        resolver.resolve(null, "x");
        assertTrue(message.get().contains("x \u2192 y \u2192 x"), "expected path in: " + message.get());
    }

    @Test
    void validChainAtExactDepthLimitResolves() {
        VariableResolver resolver = resolver(settings(3, 100, 100), Map.of(
                "a", variable("a", "%plexvar_b%"),
                "b", variable("b", "%plexvar_c%"),
                "c", variable("c", "end")), (player, token) -> token);
        assertEquals("end", resolver.resolve(null, "a"));
    }

    @Test
    void depthLimitStopsAnAcyclicChain() {
        VariableResolver resolver = resolver(settings(2, 100, 100), Map.of(
                "a", variable("a", "%plexvar_b%"),
                "b", variable("b", "%plexvar_c%"),
                "c", variable("c", "end")), (player, token) -> token);
        assertEquals(ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void workLimitStopsBranchingReferences() {
        VariableResolver resolver = resolver(settings(10, 3, 100), Map.of(
                "a", variable("a", "%plexvar_b%:%plexvar_b%:%plexvar_b%"),
                "b", variable("b", "x")), (player, token) -> token);
        assertEquals("x:x:" + ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void outputLimitStopsGrowthInParentEvenWhenChildrenFit() {
        VariableResolver resolver = resolver(settings(10, 100, 7), Map.of(
                "a", variable("a", "%plexvar_b%%plexvar_b%"),
                "b", variable("b", "1234")), (player, token) -> token);
        assertEquals(ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void externalCallbackReentrySharesCycleGuard() {
        AtomicReference<VariableResolver> reference = new AtomicReference<>();
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of("a", variable("a", "%external_call%")),
                (player, token) -> reference.get().resolve(player, "a"));
        reference.set(resolver);
        assertEquals(ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void parserOutputIsTerminalAndCannotTriggerAccidentalSecondPass() {
        AtomicInteger calls = new AtomicInteger();
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of("a", variable("a", "%external_x%")),
                (player, token) -> {
                    calls.incrementAndGet();
                    return "%plexvar_missing%";
                });
        assertEquals("%plexvar_missing%", resolver.resolve(null, "a"));
        assertEquals(1, calls.get());
    }

    @Test
    void rootOutputConvertsLegacyAndHexColorCodes() {
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(
                "a", variable("a", "&aGreen %plexvar_b%"),
                "b", variable("b", "&#12abefHex")), (player, token) -> token);
        assertEquals("§aGreen §x§1§2§a§b§e§fHex", resolver.resolve(null, "a"));
        assertEquals("§aGreen", resolver.parse(null, "&aGreen"));
    }

    @Test
    void colorConversionCanBeDisabled() {
        PluginSettings rawSettings = new PluginSettings(10, 100, 100, 60, 10, ERROR, false);
        VariableResolver resolver = resolver(rawSettings, Map.of("a", variable("a", "&aGreen &#12abefHex")),
                (player, token) -> token);
        assertEquals("&aGreen &#12abefHex", resolver.resolve(null, "a"));
    }

    @Test
    void serializedColorCodesCannotExceedOutputLimit() {
        VariableResolver resolver = resolver(settings(10, 100, 9), Map.of("a", variable("a", "&#12abefX")),
                (player, token) -> token);
        assertEquals(ERROR, resolver.resolve(null, "a"));
    }

    @Test
    void callbackReentryReturnsRawColorTextUntilOutermostReturn() {
        AtomicReference<VariableResolver> reference = new AtomicReference<>();
        AtomicReference<String> nested = new AtomicReference<>();
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of(
                "a", variable("a", "%external_x%"), "b", variable("b", "&aGreen")),
                (player, token) -> {
                    nested.set(reference.get().resolve(player, "b"));
                    return nested.get();
                });
        reference.set(resolver);
        assertEquals("§aGreen", resolver.resolve(null, "a"));
        assertEquals("&aGreen", nested.get());
    }

    @Test
    void eachRootRequestKeepsItsOriginalSnapshot() {
        AtomicReference<PluginState> state = new AtomicReference<>(state(settings(10, 100, 100), Map.of(
                "a", variable("a", "%external_swap%:%plexvar_b%"), "b", variable("b", "old"))));
        VariableResolver resolver = new VariableResolver(state::get, (player, token) -> {
            state.set(state(settings(10, 100, 100), Map.of("a", variable("a", "new"), "b", variable("b", "new"))));
            return "swap";
        }, logger());
        assertEquals("swap:old", resolver.resolve(null, "a"));
        assertEquals("new", resolver.resolve(null, "a"));
    }

    @Test
    void concurrentRequestsHaveSeparateSnapshotsAndGuards() throws Exception {
        AtomicReference<PluginState> state = new AtomicReference<>(state(settings(10, 100, 100), Map.of(
                "a", variable("a", "%external_hold%:%plexvar_b%"), "b", variable("b", "old"))));
        CountDownLatch enteredParser = new CountDownLatch(1);
        CountDownLatch releaseParser = new CountDownLatch(1);
        VariableResolver resolver = new VariableResolver(state::get, (player, token) -> {
            enteredParser.countDown();
            try {
                if (!releaseParser.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return "held";
        }, logger());
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<String> oldRequest = workers.submit(() -> resolver.resolve(null, "a"));
            assertTrue(enteredParser.await(2, TimeUnit.SECONDS));
            state.set(state(settings(10, 100, 100), Map.of("a", variable("a", "new"))));
            assertEquals("new", resolver.resolve(null, "a"));
            releaseParser.countDown();
            assertEquals("held:old", oldRequest.get(2, TimeUnit.SECONDS));
        } finally {
            releaseParser.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void parserExceptionReturnsFallbackAndCleansContext() {
        AtomicInteger calls = new AtomicInteger();
        VariableResolver resolver = resolver(settings(10, 100, 100), Map.of("a", variable("a", "%external_x%")),
                (player, token) -> {
                    if (calls.incrementAndGet() == 1) throw new IllegalStateException("boom");
                    return "ok";
                });
        assertEquals(ERROR, resolver.resolve(null, "a"));
        assertEquals("ok", resolver.resolve(null, "a"));
    }

    @Test
    void warningIsSourceAwareThrottledAndResettable() {
        Logger logger = logger();
        AtomicInteger warnings = new AtomicInteger();
        AtomicReference<String> message = new AtomicReference<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                warnings.incrementAndGet();
                message.set(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        VariableResolver resolver = new VariableResolver(() -> state(settings(10, 100, 100),
                Map.of("a", variable("a", "%plexvar_a%"))), (player, token) -> token, logger);
        resolver.resolve(null, "a");
        resolver.resolve(null, "a");
        assertEquals(1, warnings.get());
        assertTrue(message.get().contains("a.yml"));
        resolver.clearWarnings();
        resolver.resolve(null, "a");
        assertEquals(2, warnings.get());
    }

    @Test
    void manyDistinctFailuresCannotFloodTheLog() {
        Logger logger = logger();
        AtomicInteger warnings = new AtomicInteger();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.incrementAndGet(); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Map<String, VariableDefinition> variables = new HashMap<>();
        for (int index = 0; index < 40; index++) {
            String id = "v" + index;
            variables.put(id, variable(id, "%plexvar_" + id + "%"));
        }
        VariableResolver resolver = new VariableResolver(() -> state(settings(10, 100, 100), variables),
                (player, token) -> token, logger);
        for (String id : variables.keySet()) resolver.resolve(null, id);
        assertTrue(warnings.get() <= 32, "distinct bad values should have a global warning cap");
    }

    private static VariableResolver resolver(PluginSettings settings, Map<String, VariableDefinition> variables,
                                             java.util.function.BiFunction<OfflinePlayer, String, String> parser) {
        PluginState state = state(settings, variables);
        return new VariableResolver(() -> state, parser, logger());
    }

    private static PluginState state(PluginSettings settings, Map<String, VariableDefinition> variables) {
        return new PluginState(settings, MessageUtil.from(new YamlConfiguration()), variables, 0);
    }

    private static PluginSettings settings(int depth, int work, int output) {
        return new PluginSettings(depth, work, output, 60, 10, ERROR, true);
    }

    private static VariableDefinition variable(String id, String value) {
        return new VariableDefinition(id, value, id + ".yml", VariableType.STATIC);
    }

    private static Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }
}
