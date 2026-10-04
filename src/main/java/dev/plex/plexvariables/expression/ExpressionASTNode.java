package dev.plex.plexvariables.expression;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public sealed interface ExpressionASTNode {

    record NumberNode(BigDecimal value) implements ExpressionASTNode {
        public NumberNode {
            Objects.requireNonNull(value, "value");
        }
    }

    record PlaceholderNode(String rawToken) implements ExpressionASTNode {
        public PlaceholderNode {
            Objects.requireNonNull(rawToken, "rawToken");
        }
    }

    record UnaryOpNode(Operator op, ExpressionASTNode operand) implements ExpressionASTNode {
        public UnaryOpNode {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(operand, "operand");
        }

        public enum Operator {
            NEGATE,
            PLUS
        }
    }

    record BinaryOpNode(Operator op, ExpressionASTNode left, ExpressionASTNode right) implements ExpressionASTNode {
        public BinaryOpNode {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }

        public enum Operator {
            ADD,
            SUBTRACT,
            MULTIPLY,
            DIVIDE,
            MODULO,
            POWER
        }
    }

    record FunctionNode(String name, List<ExpressionASTNode> args) implements ExpressionASTNode {
        public FunctionNode {
            Objects.requireNonNull(name, "name");
            args = List.copyOf(args);
        }
    }
}
