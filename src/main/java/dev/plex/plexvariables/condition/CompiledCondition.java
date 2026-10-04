package dev.plex.plexvariables.condition;

import java.util.regex.Pattern;

public final class CompiledCondition {
    private final String rawCondition;
    private final String value;
    private final ConditionType type;
    private final boolean negated;
    private final String targetPermission;
    private final String targetWorld;
    private final String leftExpression;
    private final ConditionOperator operator;
    private final String rightExpression;
    private final Pattern staticPattern;

    private CompiledCondition(String rawCondition, String value, ConditionType type, boolean negated,
                              String targetPermission, String targetWorld, String leftExpression,
                              ConditionOperator operator, String rightExpression, Pattern staticPattern) {
        this.rawCondition = rawCondition;
        this.value = value;
        this.type = type;
        this.negated = negated;
        this.targetPermission = targetPermission;
        this.targetWorld = targetWorld;
        this.leftExpression = leftExpression;
        this.operator = operator;
        this.rightExpression = rightExpression;
        this.staticPattern = staticPattern;
    }

    public static CompiledCondition comparison(String rawCondition, String value, String leftExpression,
                                                ConditionOperator operator, String rightExpression,
                                                Pattern staticPattern) {
        return new CompiledCondition(rawCondition, value, ConditionType.COMPARISON, false,
                null, null, leftExpression, operator, rightExpression, staticPattern);
    }

    public static CompiledCondition permission(String rawCondition, String value, String permission, boolean negated) {
        return new CompiledCondition(rawCondition, value, ConditionType.PERMISSION, negated,
                permission, null, null, null, null, null);
    }

    public static CompiledCondition world(String rawCondition, String value, String world, boolean negated) {
        return new CompiledCondition(rawCondition, value, ConditionType.WORLD, negated,
                null, world, null, null, null, null);
    }

    public String rawCondition() {
        return rawCondition;
    }

    public String value() {
        return value;
    }

    public ConditionType type() {
        return type;
    }

    public boolean isNegated() {
        return negated;
    }

    public String targetPermission() {
        return targetPermission;
    }

    public String targetWorld() {
        return targetWorld;
    }

    public String leftExpression() {
        return leftExpression;
    }

    public ConditionOperator operator() {
        return operator;
    }

    public String rightExpression() {
        return rightExpression;
    }

    public Pattern staticPattern() {
        return staticPattern;
    }
}
