package dev.plex.plexvariables.condition;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ConditionParser {

    private static final String PERMISSION_PREFIX = "permission:";
    private static final String NOT_PERMISSION_PREFIX = "!permission:";
    private static final String WORLD_PREFIX = "world:";
    private static final String NOT_WORLD_PREFIX = "!world:";

    private ConditionParser() {
    }

    public static CompiledCondition parse(String rawCondition, String value) {
        Objects.requireNonNull(rawCondition, "rawCondition");
        Objects.requireNonNull(value, "value");

        String trimmed = rawCondition.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Condition string cannot be empty");
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.startsWith(NOT_PERMISSION_PREFIX)) {
            String perm = trimmed.substring(NOT_PERMISSION_PREFIX.length()).trim();
            if (perm.isEmpty()) {
                throw new IllegalArgumentException("Invalid permission condition: missing permission node");
            }
            return CompiledCondition.permission(rawCondition, value, perm, true);
        }

        if (lower.startsWith(PERMISSION_PREFIX)) {
            String perm = trimmed.substring(PERMISSION_PREFIX.length()).trim();
            if (perm.isEmpty()) {
                throw new IllegalArgumentException("Invalid permission condition: missing permission node");
            }
            return CompiledCondition.permission(rawCondition, value, perm, false);
        }

        if (lower.startsWith(NOT_WORLD_PREFIX)) {
            String world = trimmed.substring(NOT_WORLD_PREFIX.length()).trim();
            if (world.isEmpty()) {
                throw new IllegalArgumentException("Invalid world condition: missing world name");
            }
            return CompiledCondition.world(rawCondition, value, world, true);
        }

        if (lower.startsWith(WORLD_PREFIX)) {
            String world = trimmed.substring(WORLD_PREFIX.length()).trim();
            if (world.isEmpty()) {
                throw new IllegalArgumentException("Invalid world condition: missing world name");
            }
            return CompiledCondition.world(rawCondition, value, world, false);
        }

        OperatorMatch match = findOperator(trimmed);
        if (match == null) {
            throw new IllegalArgumentException("Could not parse condition: missing or unrecognized operator in '" + rawCondition + "'");
        }

        String leftRaw = trimmed.substring(0, match.startIndex).trim();
        String rightRaw = trimmed.substring(match.endIndex).trim();

        if (leftRaw.isEmpty() || rightRaw.isEmpty()) {
            throw new IllegalArgumentException("Condition requires both left and right operands: '" + rawCondition + "'");
        }

        String left = unquote(leftRaw);
        String right = unquote(rightRaw);

        Pattern staticPattern = null;
        if (match.operator == ConditionOperator.MATCHES && !right.contains("%")) {
            try {
                staticPattern = Pattern.compile(right);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException("Invalid regex pattern '" + right + "': " + exception.getMessage(), exception);
            }
        }

        return CompiledCondition.comparison(rawCondition, value, left, match.operator, right, staticPattern);
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    private static OperatorMatch findOperator(String text) {
        boolean inDouble = false;
        boolean inSingle = false;

        OperatorMatch bestMatch = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (inDouble || inSingle) {
                continue;
            }

            for (ConditionOperator op : ConditionOperator.values()) {
                String symbol = op.symbol();
                if (text.regionMatches(true, i, symbol, 0, symbol.length())) {
                    if (isWordOperator(op)) {
                        boolean leftBoundary = i == 0 || Character.isWhitespace(text.charAt(i - 1));
                        int after = i + symbol.length();
                        boolean rightBoundary = after == text.length() || Character.isWhitespace(text.charAt(after));
                        if (!leftBoundary || !rightBoundary) {
                            continue;
                        }
                    }

                    OperatorMatch current = new OperatorMatch(op, i, i + symbol.length());
                    if (bestMatch == null || current.startIndex < bestMatch.startIndex
                            || (current.startIndex == bestMatch.startIndex && symbol.length() > bestMatch.operator.symbol().length())) {
                        bestMatch = current;
                    }
                }
            }

            if (bestMatch != null && bestMatch.startIndex == i) {
                return bestMatch;
            }
        }

        return bestMatch;
    }

    private static boolean isWordOperator(ConditionOperator op) {
        return op == ConditionOperator.NOT_CONTAINS
                || op == ConditionOperator.CONTAINS
                || op == ConditionOperator.STARTS_WITH
                || op == ConditionOperator.ENDS_WITH
                || op == ConditionOperator.MATCHES;
    }

    private static final class OperatorMatch {
        private final ConditionOperator operator;
        private final int startIndex;
        private final int endIndex;

        private OperatorMatch(ConditionOperator operator, int startIndex, int endIndex) {
            this.operator = operator;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }
}
