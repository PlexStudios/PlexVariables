package dev.plex.plexvariables.condition;

import java.util.Locale;

public enum ConditionOperator {
    EQUALS("=="),
    NOT_EQUALS("!="),
    GREATER_THAN_OR_EQUAL(">="),
    GREATER_THAN(">"),
    LESS_THAN_OR_EQUAL("<="),
    LESS_THAN("<"),
    NOT_CONTAINS("!contains"),
    CONTAINS("contains"),
    STARTS_WITH("startsWith"),
    ENDS_WITH("endsWith"),
    MATCHES("matches");

    private final String symbol;

    ConditionOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isNumericOnly() {
        return this == GREATER_THAN || this == GREATER_THAN_OR_EQUAL
                || this == LESS_THAN || this == LESS_THAN_OR_EQUAL;
    }

    public static ConditionOperator fromSymbol(String symbol) {
        if (symbol == null) return null;
        String lower = symbol.trim().toLowerCase(Locale.ROOT);
        for (ConditionOperator op : values()) {
            if (op.symbol.equalsIgnoreCase(lower)) {
                return op;
            }
        }
        return null;
    }
}
