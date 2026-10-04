package dev.plex.plexvariables.expression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

public record ExpressionFormatting(
        int decimals,
        boolean stripTrailingZeros,
        RoundingMode roundingMode,
        String prefix,
        String suffix,
        boolean thousandsSeparator
) {
    public ExpressionFormatting {
        Objects.requireNonNull(roundingMode, "roundingMode");
    }

    public static final ExpressionFormatting DEFAULT = new ExpressionFormatting(
            -1,
            true,
            RoundingMode.HALF_UP,
            null,
            null,
            false
    );

    public String formatNumber(BigDecimal value) {
        if (value == null) return "";
        BigDecimal bd = value;

        if (decimals >= 0) {
            bd = bd.setScale(decimals, roundingMode);
        }

        if (thousandsSeparator) {
            DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
            symbols.setGroupingSeparator(',');
            symbols.setDecimalSeparator('.');

            DecimalFormat df = new DecimalFormat();
            df.setDecimalFormatSymbols(symbols);
            df.setGroupingUsed(true);
            df.setGroupingSize(3);

            if (decimals >= 0) {
                df.setMinimumFractionDigits(stripTrailingZeros ? 0 : decimals);
                df.setMaximumFractionDigits(decimals);
            } else {
                df.setMinimumFractionDigits(0);
                df.setMaximumFractionDigits(16);
            }
            return df.format(bd);
        }

        if (stripTrailingZeros && (decimals < 0 || bd.scale() > 0)) {
            bd = bd.stripTrailingZeros();
        }
        return bd.toPlainString();
    }
}
