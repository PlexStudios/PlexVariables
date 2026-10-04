package dev.plex.plexvariables.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ExpressionParser {
    private final List<ExpressionToken> tokens;
    private final int maxParenthesisDepth;
    private int current = 0;
    private int parenDepth = 0;
    private final List<String> placeholders = new ArrayList<>();

    private ExpressionParser(List<ExpressionToken> tokens, int maxParenthesisDepth) {
        this.tokens = tokens;
        this.maxParenthesisDepth = maxParenthesisDepth;
    }

    public static CompiledExpression parse(String rawExpression, int maxLength, int maxTokens, int maxParenthesisDepth) {
        List<ExpressionToken> tokens = ExpressionTokenizer.tokenize(rawExpression, maxLength, maxTokens);
        ExpressionParser parser = new ExpressionParser(tokens, maxParenthesisDepth);
        ExpressionASTNode root = parser.parseExpression();
        if (parser.current < parser.tokens.size()) {
            ExpressionToken token = parser.tokens.get(parser.current);
            throw new ExpressionException("Unexpected token '" + token.value() + "' at position " + token.position());
        }
        return new CompiledExpression(rawExpression, root, parser.placeholders);
    }

    private ExpressionASTNode parseExpression() {
        return parseAdditive();
    }

    private ExpressionASTNode parseAdditive() {
        ExpressionASTNode left = parseMultiplicative();
        while (matchOperator("+", "-")) {
            ExpressionToken opToken = previous();
            ExpressionASTNode.BinaryOpNode.Operator op = opToken.value().equals("+")
                    ? ExpressionASTNode.BinaryOpNode.Operator.ADD
                    : ExpressionASTNode.BinaryOpNode.Operator.SUBTRACT;
            ExpressionASTNode right = parseMultiplicative();
            left = new ExpressionASTNode.BinaryOpNode(op, left, right);
        }
        return left;
    }

    private ExpressionASTNode parseMultiplicative() {
        ExpressionASTNode left = parsePower();
        while (matchOperator("*", "/", "%")) {
            ExpressionToken opToken = previous();
            ExpressionASTNode.BinaryOpNode.Operator op = switch (opToken.value()) {
                case "*" -> ExpressionASTNode.BinaryOpNode.Operator.MULTIPLY;
                case "/" -> ExpressionASTNode.BinaryOpNode.Operator.DIVIDE;
                case "%" -> ExpressionASTNode.BinaryOpNode.Operator.MODULO;
                default -> throw new IllegalStateException("Unexpected operator: " + opToken.value());
            };
            ExpressionASTNode right = parsePower();
            left = new ExpressionASTNode.BinaryOpNode(op, left, right);
        }
        return left;
    }

    private ExpressionASTNode parsePower() {
        ExpressionASTNode left = parseUnary();
        if (matchOperator("^")) {
            ExpressionASTNode right = parsePower();
            return new ExpressionASTNode.BinaryOpNode(ExpressionASTNode.BinaryOpNode.Operator.POWER, left, right);
        }
        return left;
    }

    private ExpressionASTNode parseUnary() {
        if (match(ExpressionToken.Type.UNARY_MINUS)) {
            ExpressionASTNode operand = parseUnary();
            return new ExpressionASTNode.UnaryOpNode(ExpressionASTNode.UnaryOpNode.Operator.NEGATE, operand);
        }
        if (match(ExpressionToken.Type.UNARY_PLUS)) {
            ExpressionASTNode operand = parseUnary();
            return new ExpressionASTNode.UnaryOpNode(ExpressionASTNode.UnaryOpNode.Operator.PLUS, operand);
        }
        return parsePrimary();
    }

    private ExpressionASTNode parsePrimary() {
        if (match(ExpressionToken.Type.NUMBER)) {
            try {
                BigDecimal val = new BigDecimal(previous().value());
                return new ExpressionASTNode.NumberNode(val);
            } catch (NumberFormatException e) {
                throw new ExpressionException("Invalid numeric token '" + previous().value() + "' at position " + previous().position());
            }
        }

        if (match(ExpressionToken.Type.PLACEHOLDER)) {
            String tokenStr = previous().value();
            if (!placeholders.contains(tokenStr)) {
                placeholders.add(tokenStr);
            }
            return new ExpressionASTNode.PlaceholderNode(tokenStr);
        }

        if (match(ExpressionToken.Type.FUNCTION)) {
            String funcName = previous().value();
            consume(ExpressionToken.Type.LPAREN, "Expected '(' after function name '" + funcName + "'");
            parenDepth++;
            if (parenDepth > maxParenthesisDepth) {
                throw new ExpressionException("Expression exceeds maximum parenthesis depth of " + maxParenthesisDepth);
            }

            List<ExpressionASTNode> args = new ArrayList<>();
            if (!check(ExpressionToken.Type.RPAREN)) {
                do {
                    args.add(parseExpression());
                } while (match(ExpressionToken.Type.COMMA));
            }
            consume(ExpressionToken.Type.RPAREN, "Expected ')' after arguments for function '" + funcName + "'");
            parenDepth--;

            validateFunctionArgs(funcName, args);
            return new ExpressionASTNode.FunctionNode(funcName, args);
        }

        if (match(ExpressionToken.Type.LPAREN)) {
            parenDepth++;
            if (parenDepth > maxParenthesisDepth) {
                throw new ExpressionException("Expression exceeds maximum parenthesis depth of " + maxParenthesisDepth);
            }
            ExpressionASTNode expr = parseExpression();
            consume(ExpressionToken.Type.RPAREN, "Expected ')' after expression");
            parenDepth--;
            return expr;
        }

        if (isAtEnd()) {
            throw new ExpressionException("Unexpected end of expression");
        }

        ExpressionToken token = peek();
        throw new ExpressionException("Unexpected token '" + token.value() + "' at position " + token.position());
    }

    private static void validateFunctionArgs(String name, List<ExpressionASTNode> args) {
        switch (name) {
            case "min", "max" -> {
                if (args.size() != 2) {
                    throw new ExpressionException("Function '" + name + "' requires exactly 2 arguments, found " + args.size());
                }
            }
            case "abs", "round", "floor", "ceil", "sqrt" -> {
                if (args.size() != 1) {
                    throw new ExpressionException("Function '" + name + "' requires exactly 1 argument, found " + args.size());
                }
            }
            default -> throw new ExpressionException("Unknown function '" + name + "'");
        }
    }

    private boolean matchOperator(String... ops) {
        if (check(ExpressionToken.Type.OPERATOR)) {
            String val = peek().value();
            for (String op : ops) {
                if (val.equals(op)) {
                    advance();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean match(ExpressionToken.Type type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean check(ExpressionToken.Type type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    private ExpressionToken advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return current >= tokens.size();
    }

    private ExpressionToken peek() {
        return tokens.get(current);
    }

    private ExpressionToken previous() {
        return tokens.get(current - 1);
    }

    private ExpressionToken consume(ExpressionToken.Type type, String message) {
        if (check(type)) return advance();
        int pos = isAtEnd() ? tokens.get(tokens.size() - 1).position() : peek().position();
        throw new ExpressionException(message + " at position " + pos);
    }
}
