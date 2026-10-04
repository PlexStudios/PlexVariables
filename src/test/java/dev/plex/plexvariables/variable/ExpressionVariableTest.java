package dev.plex.plexvariables.variable;

import dev.plex.plexvariables.condition.CompiledCondition;
import dev.plex.plexvariables.condition.ConditionParser;
import dev.plex.plexvariables.condition.EvaluationTrace;
import dev.plex.plexvariables.config.PluginSettings;
import dev.plex.plexvariables.config.PluginState;
import dev.plex.plexvariables.expression.CompiledExpression;
import dev.plex.plexvariables.expression.ExpressionException;
import dev.plex.plexvariables.expression.ExpressionFormatting;
import dev.plex.plexvariables.expression.ExpressionParser;
import dev.plex.plexvariables.util.MessageUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionVariableTest {

    private static final String ERROR = "[error]";

    @Test
    void basicArithmeticAndPrecedence() {
        VariableResolver resolver = createResolver(Map.of(
                "add", expr("add", "1 + 2"),
                "sub", expr("sub", "10 - 4"),
                "mul", expr("mul", "5 * 3"),
                "div", expr("div", "10 / 2"),
                "mod", expr("mod", "10 % 3"),
                "pow", expr("pow", "2 ^ 3"),
                "prec", expr("prec", "2 + 3 * 4"),
                "paren", expr("paren", "(2 + 3) * 4"),
                "nested_paren", expr("nested_paren", "((10 + 5) * 2) / 3")
        ));

        assertEquals("3", resolver.resolve(null, "add"));
        assertEquals("6", resolver.resolve(null, "sub"));
        assertEquals("15", resolver.resolve(null, "mul"));
        assertEquals("5", resolver.resolve(null, "div"));
        assertEquals("1", resolver.resolve(null, "mod"));
        assertEquals("8", resolver.resolve(null, "pow"));
        assertEquals("14", resolver.resolve(null, "prec"));
        assertEquals("20", resolver.resolve(null, "paren"));
        assertEquals("10", resolver.resolve(null, "nested_paren"));
    }

    @Test
    void rightAssociativeExponentiation() {
        VariableResolver resolver = createResolver(Map.of(
                "pow_assoc", expr("pow_assoc", "2 ^ 3 ^ 2")
        ));
        assertEquals("512", resolver.resolve(null, "pow_assoc"));
    }

    @Test
    void unaryOperators() {
        VariableResolver resolver = createResolver(Map.of(
                "neg", expr("neg", "-5 + 10"),
                "neg_paren", expr("neg_paren", "-(5 + 3)"),
                "plus", expr("plus", "+5")
        ));

        assertEquals("5", resolver.resolve(null, "neg"));
        assertEquals("-8", resolver.resolve(null, "neg_paren"));
        assertEquals("5", resolver.resolve(null, "plus"));
    }

    @Test
    void decimalPrecisionWithoutFloatingPointErrors() {
        VariableResolver resolver = createResolver(Map.of(
                "dec", expr("dec", "0.1 + 0.2")
        ));
        assertEquals("0.3", resolver.resolve(null, "dec"));
    }

    @Test
    void divisionAndModuloByZero() {
        VariableResolver resolver = createResolver(Map.of(
                "div_zero_fallback", expr("div_zero_fallback", "10 / 0", fmt(-1, true, false), "0"),
                "div_zero_default", expr("div_zero_default", "10 / 0"),
                "mod_zero_default", expr("mod_zero_default", "10 % 0")
        ));

        assertEquals("0", resolver.resolve(null, "div_zero_fallback"));
        assertEquals(ERROR, resolver.resolve(null, "div_zero_default"));
        assertEquals(ERROR, resolver.resolve(null, "mod_zero_default"));
    }

    @Test
    void mathematicalFunctions() {
        VariableResolver resolver = createResolver(Map.of(
                "min", expr("min", "min(10, 5)"),
                "max", expr("max", "max(10, 5)"),
                "abs", expr("abs", "abs(-15)"),
                "round", expr("round", "round(2.6)"),
                "floor", expr("floor", "floor(2.9)"),
                "ceil", expr("ceil", "ceil(2.1)"),
                "sqrt", expr("sqrt", "sqrt(16)"),
                "sqrt_neg", expr("sqrt_neg", "sqrt(-4)", fmt(-1, true, false), "invalid")
        ));

        assertEquals("5", resolver.resolve(null, "min"));
        assertEquals("10", resolver.resolve(null, "max"));
        assertEquals("15", resolver.resolve(null, "abs"));
        assertEquals("3", resolver.resolve(null, "round"));
        assertEquals("2", resolver.resolve(null, "floor"));
        assertEquals("3", resolver.resolve(null, "ceil"));
        assertEquals("4", resolver.resolve(null, "sqrt"));
        assertEquals("invalid", resolver.resolve(null, "sqrt_neg"));
    }

    @Test
    void formattingOptions() {
        VariableResolver resolver = createResolver(Map.of(
                "stripped", expr("stripped", "2.50", fmt(2, true, false), null),
                "kept", expr("kept", "2.50", fmt(2, false, false), null),
                "prefix_suffix", expr("prefix_suffix", "15.5", fmt(2, true, false, "$", " USD"), null),
                "thousands", expr("thousands", "1234567.89", fmt(2, false, true), null)
        ));

        assertEquals("2.5", resolver.resolve(null, "stripped"));
        assertEquals("2.50", resolver.resolve(null, "kept"));
        assertEquals("$15.5 USD", resolver.resolve(null, "prefix_suffix"));
        assertEquals("1,234,567.89", resolver.resolve(null, "thousands"));
    }

    @Test
    void placeholderSubstitutionAndModuloVsToken() {
        VariableResolver resolver = createResolver(Map.of(
                "mod_token", expr("mod_token", "%kills% % 10"),
                "kd", expr("kd", "%kills% / %deaths%", fmt(2, true, false), "0")
        ), (player, token) -> switch (token) {
            case "%kills%" -> "25";
            case "%deaths%" -> "10";
            default -> token;
        });

        assertEquals("5", resolver.resolve(null, "mod_token"));
        assertEquals("2.5", resolver.resolve(null, "kd"));
    }

    @Test
    void invalidPlaceholderOutputHandledSafely() {
        VariableResolver resolver = createResolver(Map.of(
                "invalid_val", expr("invalid_val", "%papi_val% + 5", fmt(-1, true, false), "0")
        ), (player, token) -> "%papi_val%".equals(token) ? "N/A" : token);

        assertEquals("0", resolver.resolve(null, "invalid_val"));
    }

    @Test
    void unresolvedPlaceholderFailsSafely() {
        VariableResolver resolver = createResolver(Map.of(
                "unresolved", expr("unresolved", "%unknown_papi% + 5", fmt(-1, true, false), "fallback")
        ));

        assertEquals("fallback", resolver.resolve(null, "unresolved"));
    }

    @Test
    void mixedVariableTypesAndChains() {
        VariableResolver resolver = createResolver(Map.of(
                "base_static", VariableDefinition.ofStatic("base_static", "10", "static.yml"),
                "expr_from_static", expr("expr_from_static", "%plexvar_base_static% * 2"),

                "expr_base", expr("expr_base", "5 + 5"),
                "static_from_expr", VariableDefinition.ofStatic("static_from_expr", "Score: %plexvar_expr_base%", "static.yml"),

                "bonus_cond", VariableDefinition.ofConditional("bonus_cond", List.of(
                        ConditionParser.parse("permission:vip", "2")
                ), "1", "cond.yml"),
                "score_expr", expr("score_expr", "10 * %plexvar_bonus_cond%"),

                "winrate_expr", expr("winrate_expr", "(75 / 100) * 100", fmt(1, true, false), null),
                "winrate_status", VariableDefinition.ofConditional("winrate_status", List.of(
                        ConditionParser.parse("%plexvar_winrate_expr% >= 70", "&aExcellent")
                ), "&cLow", "cond.yml")
        ));

        assertEquals("20", resolver.resolve(null, "expr_from_static"));
        assertEquals("Score: 10", resolver.resolve(null, "static_from_expr"));
        assertEquals("10", resolver.resolve(null, "score_expr"));
        assertEquals("§aExcellent", resolver.resolve(null, "winrate_status"));
    }

    @Test
    void mixedCycleProtection() {
        VariableResolver resolver = createResolver(Map.of(
                "cycle_expr_a", expr("cycle_expr_a", "%plexvar_cycle_cond_b% + 1"),
                "cycle_cond_b", VariableDefinition.ofConditional("cycle_cond_b", List.of(
                        ConditionParser.parse("1 == 1", "%plexvar_cycle_static_c%")
                ), "0", "cond.yml"),
                "cycle_static_c", VariableDefinition.ofStatic("cycle_static_c", "%plexvar_cycle_expr_a%", "static.yml")
        ));

        assertEquals(ERROR, resolver.resolve(null, "cycle_expr_a"));
    }

    @Test
    void expressionComplexityLimitsEnforced() {
        assertThrows(ExpressionException.class, () ->
                ExpressionParser.parse("1 + 2 + 3", 5, 512, 64)
        );

        assertThrows(ExpressionException.class, () ->
                ExpressionParser.parse("1 + 2 + 3 + 4 + 5", 4096, 3, 64)
        );

        assertThrows(ExpressionException.class, () ->
                ExpressionParser.parse("((((1))))", 4096, 512, 2)
        );
    }

    @Test
    void testTraceCommandReturnsExpressionTrace() {
        VariableResolver resolver = createResolver(Map.of(
                "kd_ratio", expr("kd_ratio", "%kills% / %deaths%", fmt(2, true, false), "0")
        ), (player, token) -> switch (token) {
            case "%kills%" -> "25";
            case "%deaths%" -> "10";
            default -> token;
        });

        EvaluationTrace trace = resolver.test(null, "kd_ratio");
        assertNotNull(trace);
        assertEquals("kd_ratio", trace.variableId());
        assertEquals("EXPRESSION", trace.variableType());
        assertEquals("%kills% / %deaths%", trace.rawExpression());
        assertEquals("25 / 10", trace.resolvedExpression());
        assertEquals("2.5", trace.calculatedResult());
        assertEquals("2.5", trace.finalResult());
        assertNull(trace.expressionError());
    }

    private static VariableResolver createResolver(Map<String, VariableDefinition> variables) {
        return createResolver(variables, (player, token) -> token);
    }

    private static VariableResolver createResolver(Map<String, VariableDefinition> variables,
                                                   java.util.function.BiFunction<OfflinePlayer, String, String> parser) {
        PluginSettings settings = new PluginSettings(10, 1000, 100, 60, 10, ERROR, true, false, 4096, 512, 64);
        PluginState state = new PluginState(settings, MessageUtil.from(new YamlConfiguration()), variables, 0);
        return new VariableResolver(() -> state, parser, logger());
    }

    private static VariableDefinition expr(String id, String rawExpression) {
        return expr(id, rawExpression, ExpressionFormatting.DEFAULT, null);
    }

    private static VariableDefinition expr(String id, String rawExpression, ExpressionFormatting formatting, String onError) {
        CompiledExpression compiled = ExpressionParser.parse(rawExpression, 4096, 512, 64);
        return VariableDefinition.ofExpression(id, compiled, formatting, onError, "test.yml");
    }

    private static ExpressionFormatting fmt(int decimals, boolean stripZeros, boolean thousands) {
        return new ExpressionFormatting(decimals, stripZeros, RoundingMode.HALF_UP, null, null, thousands);
    }

    private static ExpressionFormatting fmt(int decimals, boolean stripZeros, boolean thousands, String prefix, String suffix) {
        return new ExpressionFormatting(decimals, stripZeros, RoundingMode.HALF_UP, prefix, suffix, thousands);
    }

    private static Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }
}
