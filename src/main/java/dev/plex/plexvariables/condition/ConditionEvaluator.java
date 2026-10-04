package dev.plex.plexvariables.condition;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static boolean evaluate(CompiledCondition condition, OfflinePlayer player,
                                   BiFunction<OfflinePlayer, String, String> expressionResolver,
                                   boolean caseSensitive, EvaluationTrace trace, int conditionIndex) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(expressionResolver, "expressionResolver");

        if (condition.type() == ConditionType.PERMISSION) {
            boolean match = false;
            Player onlinePlayer = (player != null && player.isOnline()) ? player.getPlayer() : null;
            if (onlinePlayer != null) {
                boolean has = onlinePlayer.hasPermission(condition.targetPermission());
                match = condition.isNegated() ? !has : has;
            }
            if (trace != null) {
                String expr = condition.isNegated() ? "!" + condition.targetPermission() : condition.targetPermission();
                trace.addStep(new EvaluationTrace.Step(conditionIndex, condition.rawCondition(),
                        ConditionType.PERMISSION, expr, "permission", null, match));
            }
            return match;
        }

        if (condition.type() == ConditionType.WORLD) {
            boolean match = false;
            Player onlinePlayer = (player != null && player.isOnline()) ? player.getPlayer() : null;
            if (onlinePlayer != null) {
                boolean worldMatch = onlinePlayer.getWorld().getName().equalsIgnoreCase(condition.targetWorld());
                match = condition.isNegated() ? !worldMatch : worldMatch;
            }
            if (trace != null) {
                String expr = condition.isNegated() ? "!" + condition.targetWorld() : condition.targetWorld();
                trace.addStep(new EvaluationTrace.Step(conditionIndex, condition.rawCondition(),
                        ConditionType.WORLD, expr, "world", null, match));
            }
            return match;
        }

        String rawLeft = expressionResolver.apply(player, condition.leftExpression());
        String resolvedLeft = rawLeft != null ? rawLeft : "";

        String rawRight = expressionResolver.apply(player, condition.rightExpression());
        String resolvedRight = rawRight != null ? rawRight : "";

        ConditionOperator op = condition.operator();
        boolean match = false;

        if (op.isNumericOnly()) {
            BigDecimal numLeft = parseNumeric(resolvedLeft);
            BigDecimal numRight = parseNumeric(resolvedRight);
            if (numLeft != null && numRight != null) {
                int cmp = numLeft.compareTo(numRight);
                match = switch (op) {
                    case GREATER_THAN -> cmp > 0;
                    case GREATER_THAN_OR_EQUAL -> cmp >= 0;
                    case LESS_THAN -> cmp < 0;
                    case LESS_THAN_OR_EQUAL -> cmp <= 0;
                    default -> false;
                };
            }
        } else if (op == ConditionOperator.EQUALS || op == ConditionOperator.NOT_EQUALS) {
            BigDecimal numLeft = parseNumeric(resolvedLeft);
            BigDecimal numRight = parseNumeric(resolvedRight);
            if (numLeft != null && numRight != null) {
                match = numLeft.compareTo(numRight) == 0;
            } else if (caseSensitive) {
                match = resolvedLeft.equals(resolvedRight);
            } else {
                match = resolvedLeft.equalsIgnoreCase(resolvedRight);
            }
            if (op == ConditionOperator.NOT_EQUALS) {
                match = !match;
            }
        } else if (op == ConditionOperator.CONTAINS) {
            match = caseSensitive ? resolvedLeft.contains(resolvedRight)
                    : resolvedLeft.toLowerCase(Locale.ROOT).contains(resolvedRight.toLowerCase(Locale.ROOT));
        } else if (op == ConditionOperator.NOT_CONTAINS) {
            match = !(caseSensitive ? resolvedLeft.contains(resolvedRight)
                    : resolvedLeft.toLowerCase(Locale.ROOT).contains(resolvedRight.toLowerCase(Locale.ROOT)));
        } else if (op == ConditionOperator.STARTS_WITH) {
            match = caseSensitive ? resolvedLeft.startsWith(resolvedRight)
                    : resolvedLeft.toLowerCase(Locale.ROOT).startsWith(resolvedRight.toLowerCase(Locale.ROOT));
        } else if (op == ConditionOperator.ENDS_WITH) {
            match = caseSensitive ? resolvedLeft.endsWith(resolvedRight)
                    : resolvedLeft.toLowerCase(Locale.ROOT).endsWith(resolvedRight.toLowerCase(Locale.ROOT));
        } else if (op == ConditionOperator.MATCHES) {
            if (condition.staticPattern() != null) {
                match = condition.staticPattern().matcher(resolvedLeft).find();
            } else {
                try {
                    match = Pattern.compile(resolvedRight).matcher(resolvedLeft).find();
                } catch (PatternSyntaxException ignored) {
                    match = false;
                }
            }
        }

        if (trace != null) {
            trace.addStep(new EvaluationTrace.Step(conditionIndex, condition.rawCondition(),
                    ConditionType.COMPARISON, resolvedLeft, op.symbol(), resolvedRight, match));
        }

        return match;
    }

    private static BigDecimal parseNumeric(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
