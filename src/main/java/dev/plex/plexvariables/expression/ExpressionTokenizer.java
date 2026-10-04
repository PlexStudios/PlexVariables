package dev.plex.plexvariables.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ExpressionTokenizer {
    private static final Set<String> FUNCTIONS = Set.of(
            "min", "max", "abs", "round", "floor", "ceil", "sqrt"
    );

    private ExpressionTokenizer() {}

    public static List<ExpressionToken> tokenize(String text, int maxLength, int maxTokens) {
        if (text == null || text.isBlank()) {
            throw new ExpressionException("Expression text cannot be empty");
        }
        if (text.length() > maxLength) {
            throw new ExpressionException("Expression length (" + text.length() + ") exceeds maximum limit of " + maxLength);
        }

        List<ExpressionToken> tokens = new ArrayList<>();
        int length = text.length();
        int index = 0;

        while (index < length) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch)) {
                index++;
                continue;
            }

            if (tokens.size() >= maxTokens) {
                throw new ExpressionException("Expression exceeds maximum token count of " + maxTokens);
            }

            if (ch == '%') {
                int closing = text.indexOf('%', index + 1);
                if (closing > index + 1 && isPlaceholderBody(text, index + 1, closing)) {
                    String tokenStr = text.substring(index, closing + 1);
                    tokens.add(new ExpressionToken(ExpressionToken.Type.PLACEHOLDER, tokenStr, index));
                    index = closing + 1;
                    continue;
                } else {
                    tokens.add(new ExpressionToken(ExpressionToken.Type.OPERATOR, "%", index));
                    index++;
                    continue;
                }
            }

            if (Character.isDigit(ch) || (ch == '.' && index + 1 < length && Character.isDigit(text.charAt(index + 1)))) {
                int start = index;
                boolean hasDecimal = false;
                while (index < length) {
                    char c = text.charAt(index);
                    if (Character.isDigit(c)) {
                        index++;
                    } else if (c == '.' && !hasDecimal) {
                        hasDecimal = true;
                        index++;
                    } else {
                        break;
                    }
                }
                String numberStr = text.substring(start, index);
                tokens.add(new ExpressionToken(ExpressionToken.Type.NUMBER, numberStr, start));
                continue;
            }

            if (Character.isLetter(ch)) {
                int start = index;
                while (index < length && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) {
                    index++;
                }
                String name = text.substring(start, index);
                String lower = name.toLowerCase(Locale.ROOT);
                if (FUNCTIONS.contains(lower)) {
                    tokens.add(new ExpressionToken(ExpressionToken.Type.FUNCTION, lower, start));
                } else {
                    throw new ExpressionException("Unknown identifier or function '" + name + "' at position " + start);
                }
                continue;
            }

            switch (ch) {
                case '+', '-', '*', '/', '^' -> {
                    tokens.add(new ExpressionToken(ExpressionToken.Type.OPERATOR, String.valueOf(ch), index));
                    index++;
                }
                case '(' -> {
                    tokens.add(new ExpressionToken(ExpressionToken.Type.LPAREN, "(", index));
                    index++;
                }
                case ')' -> {
                    tokens.add(new ExpressionToken(ExpressionToken.Type.RPAREN, ")", index));
                    index++;
                }
                case ',' -> {
                    tokens.add(new ExpressionToken(ExpressionToken.Type.COMMA, ",", index));
                    index++;
                }
                default -> throw new ExpressionException("Unexpected character '" + ch + "' at position " + index);
            }
        }

        if (tokens.isEmpty()) {
            throw new ExpressionException("Expression contains no valid tokens");
        }

        return markUnaryOperators(tokens);
    }

    private static boolean isPlaceholderBody(String text, int start, int end) {
        if (start >= end) return false;
        for (int k = start; k < end; k++) {
            char c = text.charAt(k);
            if (c == '%' || c == '(' || c == ')' || c == '+' || c == '*' || c == '/' || c == '^' || c == ',' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static List<ExpressionToken> markUnaryOperators(List<ExpressionToken> tokens) {
        List<ExpressionToken> result = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            ExpressionToken token = tokens.get(i);
            if (token.type() == ExpressionToken.Type.OPERATOR
                    && (token.value().equals("+") || token.value().equals("-"))) {
                boolean isUnary = i == 0;
                if (!isUnary) {
                    ExpressionToken.Type prevType = result.get(i - 1).type();
                    isUnary = (prevType == ExpressionToken.Type.OPERATOR
                            || prevType == ExpressionToken.Type.LPAREN
                            || prevType == ExpressionToken.Type.COMMA
                            || prevType == ExpressionToken.Type.UNARY_MINUS
                            || prevType == ExpressionToken.Type.UNARY_PLUS);
                }
                if (isUnary) {
                    ExpressionToken.Type unaryType = token.value().equals("-")
                            ? ExpressionToken.Type.UNARY_MINUS
                            : ExpressionToken.Type.UNARY_PLUS;
                    result.add(new ExpressionToken(unaryType, token.value(), token.position()));
                    continue;
                }
            }
            result.add(token);
        }
        return result;
    }
}
