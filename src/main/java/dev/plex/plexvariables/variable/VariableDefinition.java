package dev.plex.plexvariables.variable;

import dev.plex.plexvariables.condition.CompiledCondition;
import dev.plex.plexvariables.expression.CompiledExpression;
import dev.plex.plexvariables.expression.ExpressionFormatting;
import dev.plex.plexvariables.storage.StoredVariableScope;

import java.util.List;
import java.util.Objects;

public record VariableDefinition(
        String id,
        VariableType type,
        String sourceFile,
        String staticValue,
        List<CompiledCondition> conditions,
        String defaultValue,
        CompiledExpression compiledExpression,
        ExpressionFormatting expressionFormatting,
        String onError,
        StoredVariableScope scope
) {
    public VariableDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceFile, "sourceFile");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public VariableDefinition(String id, VariableType type, String sourceFile, String staticValue,
                              List<CompiledCondition> conditions, String defaultValue,
                              CompiledExpression compiledExpression, ExpressionFormatting expressionFormatting,
                              String onError) {
        this(id, type, sourceFile, staticValue, conditions, defaultValue, compiledExpression, expressionFormatting, onError, null);
    }

    public VariableDefinition(String id, VariableType type, String sourceFile, String staticValue,
                              List<CompiledCondition> conditions, String defaultValue) {
        this(id, type, sourceFile, staticValue, conditions, defaultValue, null, null, null, null);
    }

    public VariableDefinition(String id, String value, String sourceFile, VariableType type) {
        this(id, type, sourceFile, value, List.of(), null, null, null, null, null);
    }

    public String value() {
        return staticValue;
    }

    public static VariableDefinition ofStatic(String id, String value, String sourceFile) {
        return new VariableDefinition(id, VariableType.STATIC, sourceFile, value, List.of(), null, null, null, null, null);
    }

    public static VariableDefinition ofConditional(String id, List<CompiledCondition> conditions, String defaultValue, String sourceFile) {
        return new VariableDefinition(id, VariableType.CONDITIONAL, sourceFile, null, conditions, defaultValue, null, null, null, null);
    }

    public static VariableDefinition ofExpression(String id, CompiledExpression compiledExpression,
                                                 ExpressionFormatting expressionFormatting, String onError, String sourceFile) {
        return new VariableDefinition(id, VariableType.EXPRESSION, sourceFile, null, List.of(), null,
                compiledExpression, expressionFormatting, onError, null);
    }

    public static VariableDefinition ofStored(String id, StoredVariableScope scope, String defaultValue, String sourceFile) {
        return new VariableDefinition(id, VariableType.STORED, sourceFile, null, List.of(), defaultValue, null, null, null, scope);
    }
}
