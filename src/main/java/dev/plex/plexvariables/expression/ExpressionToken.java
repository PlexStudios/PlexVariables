package dev.plex.plexvariables.expression;

import java.util.Objects;

public record ExpressionToken(
        Type type,
        String value,
        int position
) {
    public ExpressionToken {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    public enum Type {
        NUMBER,
        PLACEHOLDER,
        OPERATOR,
        LPAREN,
        RPAREN,
        COMMA,
        FUNCTION,
        UNARY_MINUS,
        UNARY_PLUS
    }
}
