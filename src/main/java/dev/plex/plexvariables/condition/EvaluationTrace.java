package dev.plex.plexvariables.condition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EvaluationTrace {
    private final String variableId;
    private final String variableType;
    private final String sourceFile;
    private final List<Step> steps = new ArrayList<>();
    private String selectedBranch;
    private String rawExpression;
    private String resolvedExpression;
    private String calculatedResult;
    private String expressionError;
    private String formattingSummary;
    private String storedScope;
    private String cacheState;
    private String rawStoredValue;
    private String storedDefault;
    private String effectiveValue;
    private String finalResult;
    private double elapsedMs;

    public EvaluationTrace(String variableId, String variableType, String sourceFile) {
        this.variableId = variableId;
        this.variableType = variableType;
        this.sourceFile = sourceFile;
    }

    public void addStep(Step step) {
        steps.add(step);
    }

    public void setSelectedBranch(String selectedBranch) {
        this.selectedBranch = selectedBranch;
    }

    public void setExpressionDetails(String rawExpression, String resolvedExpression, String calculatedResult, String formattingSummary) {
        this.rawExpression = rawExpression;
        this.resolvedExpression = resolvedExpression;
        this.calculatedResult = calculatedResult;
        this.formattingSummary = formattingSummary;
    }

    public void setExpressionError(String rawExpression, String resolvedExpression, String expressionError) {
        this.rawExpression = rawExpression;
        this.resolvedExpression = resolvedExpression;
        this.expressionError = expressionError;
    }

    public void setStoredDetails(String storedScope, String cacheState, String rawStoredValue, String storedDefault, String effectiveValue) {
        this.storedScope = storedScope;
        this.cacheState = cacheState;
        this.rawStoredValue = rawStoredValue;
        this.storedDefault = storedDefault;
        this.effectiveValue = effectiveValue;
    }

    public String rawExpression() { return rawExpression; }
    public String resolvedExpression() { return resolvedExpression; }
    public String calculatedResult() { return calculatedResult; }
    public String expressionError() { return expressionError; }
    public String formattingSummary() { return formattingSummary; }
    public String storedScope() { return storedScope; }
    public String cacheState() { return cacheState; }
    public String rawStoredValue() { return rawStoredValue; }
    public String storedDefault() { return storedDefault; }
    public String effectiveValue() { return effectiveValue; }

    public void setFinalResult(String finalResult) {
        this.finalResult = finalResult;
    }

    public void setElapsedMs(double elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String variableId() {
        return variableId;
    }

    public String variableType() {
        return variableType;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    public String selectedBranch() {
        return selectedBranch;
    }

    public String finalResult() {
        return finalResult;
    }

    public double elapsedMs() {
        return elapsedMs;
    }

    public record Step(
            int index,
            String rawCondition,
            ConditionType type,
            String resolvedLeft,
            String operatorSymbol,
            String resolvedRight,
            boolean result
    ) {
    }
}
