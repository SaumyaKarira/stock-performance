package com.project.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable value object representing one daily price record for a single stock symbol.
 *
 * <p>Uses {@link BigDecimal} for the close price to avoid floating-point rounding errors
 * that could silently skew performance percentages.</p>
 *
 * <p>Design decision: Java 16+ {@code record} is not used here because the source CSV
 * contains raw id_stock strings that must be normalised before the domain object is
 * constructed, making an explicit constructor with validation cleaner.</p>
 */
public final class StockPrice {

    private final String symbol;
    private final LocalDate date;
    private final BigDecimal closePrice;

    /**
     * @param symbol     ticker symbol resolved from the identifiers file (never null/blank)
     * @param date       trading date (never null)
     * @param closePrice closing price (must be positive)
     */
    public StockPrice(String symbol, LocalDate date, BigDecimal closePrice) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(closePrice, "closePrice must not be null");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (closePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("closePrice must be positive, got: " + closePrice);
        }
        this.symbol = symbol.toUpperCase();
        this.date = date;
        this.closePrice = closePrice;
    }

    public String getSymbol()       { return symbol; }
    public LocalDate getDate()      { return date; }
    public BigDecimal getClosePrice() { return closePrice; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockPrice sp)) return false;
        return symbol.equals(sp.symbol) && date.equals(sp.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, date);
    }

    @Override
    public String toString() {
        return "StockPrice{symbol='%s', date=%s, close=%s}".formatted(symbol, date, closePrice);
    }
}

