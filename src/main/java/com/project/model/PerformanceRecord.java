package com.project.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable value object representing the computed N-day performance for one symbol
 * on one date.
 *
 * <p>Performance is stored pre-rounded to 4 decimal places (basis-point precision)
 * so that every layer that uses this object works with the same value.</p>
 */
public final class PerformanceRecord {

    /** Scale used for all performance percentage values. */
    public static final int SCALE = 4;

    private final LocalDate date;
    private final String symbol;
    private final BigDecimal performance;   // percentage, rounded to SCALE dp
    private final int interval;             // trading-day interval used

    /**
     * @param date        date of the "current" price used in the calculation
     * @param symbol      ticker symbol
     * @param performance computed percentage performance (positive = gain, negative = loss)
     * @param interval    number of trading days in the lookback window
     */
    public PerformanceRecord(LocalDate date, String symbol, BigDecimal performance, int interval) {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(performance, "performance must not be null");
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive, got: " + interval);
        }
        this.date = date;
        this.symbol = symbol.toUpperCase();
        this.performance = performance.setScale(SCALE, RoundingMode.HALF_UP);
        this.interval = interval;
    }

    public LocalDate getDate()          { return date; }
    public String getSymbol()           { return symbol; }
    public BigDecimal getPerformance()  { return performance; }
    public int getInterval()            { return interval; }

    /** Convenience: performance formatted as a plain decimal string (no trailing "%" sign). */
    public String getFormattedPerformance() {
        return performance.toPlainString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PerformanceRecord pr)) return false;
        return interval == pr.interval
                && date.equals(pr.date)
                && symbol.equals(pr.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, symbol, interval);
    }

    @Override
    public String toString() {
        return "PerformanceRecord{date=%s, symbol='%s', performance=%s%%, interval=%d}"
                .formatted(date, symbol, performance.toPlainString(), interval);
    }
}

