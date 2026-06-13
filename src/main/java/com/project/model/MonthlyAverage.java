package com.project.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable value object representing the average monthly closing price
 * for one stock symbol.
 *
 * <p>Output CSV format: {@code symbol,month,average_price}
 * e.g. {@code AAPL,JAN,184.52}</p>
 *
 * <p>Uses Java 16+ {@code record} because all fields are simple and the
 * compact constructor provides the only validation needed.</p>
 */
public record MonthlyAverage(String symbol, String month, BigDecimal averagePrice) {

    /** Compact canonical constructor – validates required fields. */
    public MonthlyAverage {
        Objects.requireNonNull(symbol,       "symbol must not be null");
        Objects.requireNonNull(month,        "month must not be null");
        Objects.requireNonNull(averagePrice, "averagePrice must not be null");
        if (symbol.isBlank())
            throw new IllegalArgumentException("symbol must not be blank");
        if (month.isBlank())
            throw new IllegalArgumentException("month must not be blank");
    }
}

