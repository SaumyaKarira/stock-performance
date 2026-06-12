package com.project.service;

import com.project.model.PerformanceRecord;
import com.project.model.StockPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/**
 * Core business logic: calculates N-trading-day price performance for every
 * stock symbol present in the supplied price list.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Group price records by symbol — each group is already sorted by date
 *       because {@link CsvReaderService#read} returns a globally sorted list.</li>
 *   <li>For each symbol, iterate over its chronological price list starting at
 *       index {@code interval}.  The "previous" price is at index
 *       {@code (i - interval)}.  This index arithmetic automatically handles
 *       weekends and market holidays because they simply do not appear in the
 *       price list.</li>
 *   <li>Performance = ((current − previous) / previous) × 100, rounded to
 *       {@link PerformanceRecord#SCALE} decimal places.</li>
 * </ol>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>{@link BigDecimal} arithmetic throughout — no {@code double} division.</li>
 *   <li>A {@link MathContext} of 10 significant figures is used for the
 *       intermediate division before rounding to 4 dp, which is more than
 *       enough for percentage calculations on prices up to 5 digits.</li>
 *   <li>If a symbol has fewer than {@code interval + 1} records, a warning is
 *       logged and that symbol is silently skipped rather than throwing.</li>
 * </ul>
 */
public class PerformanceCalculator {

    private static final Logger log = LoggerFactory.getLogger(PerformanceCalculator.class);

    private static final BigDecimal HUNDRED   = BigDecimal.valueOf(100);
    private static final MathContext DIV_CTX  = new MathContext(10, RoundingMode.HALF_UP);

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Calculates performance records for every symbol in {@code prices} using
     * the given {@code interval}.
     *
     * @param prices   sorted (symbol asc, date asc) price list — must not be null
     * @param interval positive number of trading days to look back
     * @return unmodifiable list of {@link PerformanceRecord} sorted by date asc
     *         then symbol asc
     * @throws IllegalArgumentException if {@code interval} ≤ 0 or {@code prices} is null
     */
    public List<PerformanceRecord> calculate(List<StockPrice> prices, int interval) {
        Objects.requireNonNull(prices, "prices list must not be null");
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive, got: " + interval);
        }

        Map<String, List<StockPrice>> bySymbol = groupBySymbol(prices);
        List<PerformanceRecord> results = new ArrayList<>();

        for (Map.Entry<String, List<StockPrice>> entry : bySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<StockPrice> symbolPrices = entry.getValue();

            if (symbolPrices.size() <= interval) {
                log.warn("Symbol '{}' has only {} records — need at least {} for interval {}. Skipping.",
                        symbol, symbolPrices.size(), interval + 1, interval);
                continue;
            }

            log.debug("Calculating {}-day performance for '{}' ({} records).",
                    interval, symbol, symbolPrices.size());

            for (int i = interval; i < symbolPrices.size(); i++) {
                StockPrice current  = symbolPrices.get(i);
                StockPrice previous = symbolPrices.get(i - interval);

                BigDecimal perf = computePercentage(current.getClosePrice(), previous.getClosePrice());
                results.add(new PerformanceRecord(current.getDate(), symbol, perf, interval));
            }
        }

        // Final sort: date ascending, then symbol ascending (consistent output order)
        results.sort(Comparator
                .comparing(PerformanceRecord::getDate)
                .thenComparing(PerformanceRecord::getSymbol));

        log.info("Calculated {} performance records for interval={}.", results.size(), interval);
        return Collections.unmodifiableList(results);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Groups price records by symbol while preserving the date-ascending order
     * within each group (input is already sorted that way).
     */
    private Map<String, List<StockPrice>> groupBySymbol(List<StockPrice> prices) {
        Map<String, List<StockPrice>> map = new LinkedHashMap<>();
        for (StockPrice sp : prices) {
            map.computeIfAbsent(sp.getSymbol(), k -> new ArrayList<>()).add(sp);
        }
        return map;
    }

    /**
     * ((current - previous) / previous) * 100
     *
     * @param current  current close price
     * @param previous price N trading days ago
     * @return percentage performance
     */
    BigDecimal computePercentage(BigDecimal current, BigDecimal previous) {
        if (previous.compareTo(BigDecimal.ZERO) == 0) {
            // Guard against division by zero (should never happen for real stock data)
            log.warn("Previous price is zero — returning 0 performance.");
            return BigDecimal.ZERO;
        }
        return current.subtract(previous)
                      .divide(previous, DIV_CTX)
                      .multiply(HUNDRED);
    }
}

