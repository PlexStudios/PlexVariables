package dev.plex.plexvariables.variable;

import dev.plex.plexvariables.condition.CompiledCondition;
import dev.plex.plexvariables.condition.ConditionParser;
import dev.plex.plexvariables.condition.EvaluationTrace;
import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.util.MessageUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalVariableTest {

    private static final String ERROR = "[error]";

    @Test
    void numericOperatorsEvaluateCorrectly() {
        VariableResolver resolver = createResolver(Map.of(
                "greater", conditional("greater", List.of(cond("17.5 > 10", "yes")), "no"),
                "greater_equal", conditional("greater_equal", List.of(cond("10 >= 10", "yes")), "no"),
                "less", conditional("less", List.of(cond("3 < 5", "yes")), "no"),
                "less_equal", conditional("less_equal", List.of(cond("5 <= 5", "yes")), "no"),
                "numeric_equals", conditional("numeric_equals", List.of(cond("5 == 5.00", "yes")), "no"),
                "non_numeric_comparison", conditional("non_numeric_comparison", List.of(cond("abc > 5", "yes")), "no")
        ));

        assertEquals("yes", resolver.resolve(null, "greater"));
        assertEquals("yes", resolver.resolve(null, "greater_equal"));
        assertEquals("yes", resolver.resolve(null, "less"));
        assertEquals("yes", resolver.resolve(null, "less_equal"));
        assertEquals("yes", resolver.resolve(null, "numeric_equals"));
        assertEquals("no", resolver.resolve(null, "non_numeric_comparison"));
    }

    @Test
    void stringOperatorsAndCaseSensitivity() {
        VariableResolver caseInsensitive = createResolver(Map.of(
                "eq", conditional("eq", List.of(cond("\"Hello\" == \"hello\"", "yes")), "no"),
                "contains", conditional("contains", List.of(cond("\"Hello World\" contains \"WORLD\"", "yes")), "no"),
                "starts", conditional("starts", List.of(cond("\"Minecraft\" startsWith \"mine\"", "yes")), "no"),
                "ends", conditional("ends", List.of(cond("\"PaperMC\" endsWith \"mc\"", "yes")), "no")
        ), false);

        assertEquals("yes", caseInsensitive.resolve(null, "eq"));
        assertEquals("yes", caseInsensitive.resolve(null, "contains"));
        assertEquals("yes", caseInsensitive.resolve(null, "starts"));
        assertEquals("yes", caseInsensitive.resolve(null, "ends"));

        VariableResolver caseSensitive = createResolver(Map.of(
                "eq", conditional("eq", List.of(cond("\"Hello\" == \"hello\"", "yes")), "no"),
                "contains", conditional("contains", List.of(cond("\"Hello World\" contains \"WORLD\"", "yes")), "no")
        ), true);

        assertEquals("no", caseSensitive.resolve(null, "eq"));
        assertEquals("no", caseSensitive.resolve(null, "contains"));
    }

    @Test
    void regexMatchingWorksForStaticAndDynamicPatterns() {
        VariableResolver resolver = createResolver(Map.of(
                "static_regex", conditional("static_regex", List.of(cond("%test_val% matches ^[0-9]+$", "numeric")), "text"),
                "not_contains", conditional("not_contains", List.of(cond("%test_val% !contains admin", "user")), "admin")
        ), (player, token) -> token.equals("%test_val%") ? "12345" : token);

        assertEquals("numeric", resolver.resolve(null, "static_regex"));
        assertEquals("user", resolver.resolve(null, "not_contains"));
    }

    @Test
    void firstMatchWinsAndDefaultFallback() {
        VariableResolver resolver = createResolver(Map.of(
                "tier", conditional("tier", List.of(
                        cond("%score% >= 100", "Gold"),
                        cond("%score% >= 50", "Silver"),
                        cond("%score% >= 10", "Bronze")
                ), "Wood")
        ), (player, token) -> token.equals("%score%") ? "75" : token);

        assertEquals("Silver", resolver.resolve(null, "tier"));

        VariableResolver resolverLow = createResolver(Map.of(
                "tier", conditional("tier", List.of(
                        cond("%score% >= 100", "Gold"),
                        cond("%score% >= 50", "Silver")
                ), "Wood")
        ), (player, token) -> token.equals("%score%") ? "5" : token);

        assertEquals("Wood", resolverLow.resolve(null, "tier"));
    }

    @Test
    void permissionConditionsWorkForOnlinePlayerAndFailForNull() {
        Player mockOnlinePlayer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class, OfflinePlayer.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return true;
                    if ("getPlayer".equals(method.getName())) return proxy;
                    if ("hasPermission".equals(method.getName())) return "plex.admin".equals(args[0]);
                    return null;
                }
        );

        VariableResolver resolver = createResolver(Map.of(
                "admin_check", conditional("admin_check", List.of(cond("permission:plex.admin", "admin")), "user"),
                "negated_check", conditional("negated_check", List.of(cond("!permission:plex.admin", "user")), "admin")
        ));

        assertEquals("admin", resolver.resolve(mockOnlinePlayer, "admin_check"));
        assertEquals("admin", resolver.resolve(mockOnlinePlayer, "negated_check"));

        assertEquals("user", resolver.resolve(null, "admin_check"));
        assertEquals("admin", resolver.resolve(null, "negated_check"));
    }

    @Test
    void worldConditionEvaluatesCorrectly() {
        World mockWorld = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> "nether".equals(method.getName()) ? "nether" : "world"
        );

        Player mockPlayer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class, OfflinePlayer.class},
                (proxy, method, args) -> {
                    if ("isOnline".equals(method.getName())) return true;
                    if ("getPlayer".equals(method.getName())) return proxy;
                    if ("getWorld".equals(method.getName())) return mockWorld;
                    return null;
                }
        );

        VariableResolver resolver = createResolver(Map.of(
                "world_check", conditional("world_check", List.of(cond("world:world", "in_world")), "other")
        ));

        assertEquals("in_world", resolver.resolve(mockPlayer, "world_check"));
        assertEquals("other", resolver.resolve(null, "world_check"));
    }

    @Test
    void workBudgetIsConsumedPerConditionEvaluation() {
        VariableResolver resolver = createResolver(settings(10, 2, 100, false), Map.of(
                "budget_test", conditional("budget_test", List.of(
                        cond("1 == 2", "a"),
                        cond("2 == 3", "b"),
                        cond("3 == 4", "c")
                ), "default")
        ));

        assertEquals(ERROR, resolver.resolve(null, "budget_test"));
    }

    @Test
    void cycleDetectionInConditionalVariables() {
        VariableResolver resolver = createResolver(Map.of(
                "cond1", conditional("cond1", List.of(cond("1 == 1", "%plexvar_cond2%")), "def"),
                "cond2", conditional("cond2", List.of(cond("1 == 1", "%plexvar_cond1%")), "def")
        ));

        assertEquals(ERROR, resolver.resolve(null, "cond1"));
    }

    @Test
    void testTraceCommandReturnsDetailedExecutionSteps() {
        VariableResolver resolver = createResolver(Map.of(
                "rank", conditional("rank", List.of(
                        cond("%score% >= 100", "Gold"),
                        cond("%score% >= 50", "Silver")
                ), "Bronze")
        ), (player, token) -> token.equals("%score%") ? "75" : token);

        EvaluationTrace trace = resolver.test(null, "rank");
        assertNotNull(trace);
        assertEquals("rank", trace.variableId());
        assertEquals("CONDITIONAL", trace.variableType());
        assertEquals(2, trace.steps().size());
        assertFalse(trace.steps().get(0).result());
        assertTrue(trace.steps().get(1).result());
        assertEquals("Condition #2", trace.selectedBranch());
        assertEquals("Silver", trace.finalResult());
    }

    private static VariableResolver createResolver(Map<String, VariableDefinition> variables) {
        return createResolver(variables, (player, token) -> token);
    }

    private static VariableResolver createResolver(Map<String, VariableDefinition> variables, boolean caseSensitive) {
        PluginSettings settings = settings(10, 1000, 100, caseSensitive);
        PluginState state = new PluginState(settings, MessageUtil.from(new YamlConfiguration()), variables, 0);
        return new VariableResolver(() -> state, (player, token) -> token, logger());
    }

    private static VariableResolver createResolver(Map<String, VariableDefinition> variables,
                                                   java.util.function.BiFunction<OfflinePlayer, String, String> parser) {
        PluginSettings settings = settings(10, 1000, 100, false);
        PluginState state = new PluginState(settings, MessageUtil.from(new YamlConfiguration()), variables, 0);
        return new VariableResolver(() -> state, parser, logger());
    }

    private static VariableResolver createResolver(PluginSettings settings, Map<String, VariableDefinition> variables) {
        PluginState state = new PluginState(settings, MessageUtil.from(new YamlConfiguration()), variables, 0);
        return new VariableResolver(() -> state, (player, token) -> token, logger());
    }

    private static PluginSettings settings(int depth, int work, int output, boolean caseSensitive) {
        return new PluginSettings(depth, work, output, 60, 10, ERROR, true, caseSensitive);
    }

    private static CompiledCondition cond(String condition, String value) {
        return ConditionParser.parse(condition, value);
    }

    private static VariableDefinition conditional(String id, List<CompiledCondition> conditions, String defaultValue) {
        return VariableDefinition.ofConditional(id, conditions, defaultValue, id + ".yml");
    }

    private static Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }
}
