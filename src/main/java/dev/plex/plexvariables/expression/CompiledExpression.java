package dev.plex.plexvariables.expression;

import java.util.List;
import java.util.Objects;

public record CompiledExpression(
        String rawExpression,
        ExpressionASTNode astRoot,
        List<String> placeholders
) {
    public CompiledExpression {
        Objects.requireNonNull(rawExpression, "rawExpression");
        Objects.requireNonNull(astRoot, "astRoot");
        placeholders = List.copyOf(placeholders);
    }
}
