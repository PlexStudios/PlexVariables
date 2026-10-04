package dev.plex.plexvariables.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    public static BigDecimal evaluate(CompiledExpression compiled, Function<String, String> placeholderResolver) {
        Objects.requireNonNull(compiled, "compiled");
        Objects.requireNonNull(placeholderResolver, "placeholderResolver");
        return evaluateNode(compiled.astRoot(), placeholderResolver);
    }

    public static BigDecimal evaluateNode(ExpressionASTNode node, Function<String, String> placeholderResolver) {
        if (node instanceof ExpressionASTNode.NumberNode numberNode) {
            return numberNode.value();
        }

        if (node instanceof ExpressionASTNode.PlaceholderNode placeholderNode) {
            String rawToken = placeholderNode.rawToken();
            String resolved = placeholderResolver.apply(rawToken);
            if (resolved == null || resolved.indexOf('%') >= 0) {
                throw new ExpressionException("Unresolved placeholder token '" + rawToken + "' (resolved to '" + resolved + "')");
            }
            try {
                return new BigDecimal(resolved.trim());
            } catch (NumberFormatException e) {
                throw new ExpressionException("Non-numeric placeholder value '" + resolved + "' for token '" + rawToken + "'");
            }
        }

        if (node instanceof ExpressionASTNode.UnaryOpNode unaryOpNode) {
            BigDecimal val = evaluateNode(unaryOpNode.operand(), placeholderResolver);
            return switch (unaryOpNode.op()) {
                case NEGATE -> val.negate();
                case PLUS -> val;
            };
        }

        if (node instanceof ExpressionASTNode.BinaryOpNode binaryOpNode) {
            BigDecimal left = evaluateNode(binaryOpNode.left(), placeholderResolver);
            BigDecimal right = evaluateNode(binaryOpNode.right(), placeholderResolver);
            return switch (binaryOpNode.op()) {
                case ADD -> left.add(right);
                case SUBTRACT -> left.subtract(right);
                case MULTIPLY -> left.multiply(right);
                case DIVIDE -> {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ExpressionException("Division by zero");
                    }
                    yield left.divide(right, MathContext.DECIMAL128);
                }
                case MODULO -> {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ExpressionException("Modulo by zero");
                    }
                    yield left.remainder(right, MathContext.DECIMAL128);
                }
                case POWER -> calculatePower(left, right);
            };
        }

        if (node instanceof ExpressionASTNode.FunctionNode functionNode) {
            List<BigDecimal> evaluatedArgs = functionNode.args().stream()
                    .map(arg -> evaluateNode(arg, placeholderResolver))
                    .toList();
            return evaluateFunction(functionNode.name(), evaluatedArgs);
        }

        throw new ExpressionException("Unsupported AST node type: " + node.getClass().getName());
    }

    private static BigDecimal calculatePower(BigDecimal base, BigDecimal exponent) {
        if (exponent.stripTrailingZeros().scale() <= 0) {
            try {
                int expInt = exponent.intValueExact();
                if (expInt == 0) return BigDecimal.ONE;
                if (expInt > 0 && expInt <= 10000) {
                    return base.pow(expInt, MathContext.DECIMAL128);
                }
                if (expInt < 0 && expInt >= -10000) {
                    return BigDecimal.ONE.divide(base.pow(-expInt, MathContext.DECIMAL128), MathContext.DECIMAL128);
                }
            } catch (ArithmeticException ignored) {}
        }
        double res = Math.pow(base.doubleValue(), exponent.doubleValue());
        if (Double.isNaN(res) || Double.isInfinite(res)) {
            throw new ExpressionException("Power calculation overflow or invalid result for base " + base + " ^ " + exponent);
        }
        return BigDecimal.valueOf(res);
    }

    private static BigDecimal evaluateFunction(String name, List<BigDecimal> args) {
        return switch (name) {
            case "min" -> args.get(0).min(args.get(1));
            case "max" -> args.get(0).max(args.get(1));
            case "abs" -> args.get(0).abs();
            case "round" -> args.get(0).setScale(0, RoundingMode.HALF_UP);
            case "floor" -> args.get(0).setScale(0, RoundingMode.FLOOR);
            case "ceil" -> args.get(0).setScale(0, RoundingMode.CEILING);
            case "sqrt" -> {
                BigDecimal val = args.get(0);
                if (val.compareTo(BigDecimal.ZERO) < 0) {
                    throw new ExpressionException("Square root of negative number: " + val);
                }
                double res = Math.sqrt(val.doubleValue());
                if (Double.isNaN(res)) {
                    throw new ExpressionException("Invalid square root calculation for " + val);
                }
                yield BigDecimal.valueOf(res);
            }
            default -> throw new ExpressionException("Unknown function '" + name + "'");
        };
    }
}
