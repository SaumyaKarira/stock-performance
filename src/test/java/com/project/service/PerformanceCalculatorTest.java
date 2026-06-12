package com.project.service;

import com.project.model.PerformanceRecord;
import com.project.model.StockPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PerformanceCalculator}.
 *
 * <p>Tests use hand-crafted {@link StockPrice} lists so that expected performance
 * values can be computed manually and asserted exactly.</p>
 */
@DisplayName("PerformanceCalculator")
class PerformanceCalculatorTest {

    private PerformanceCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PerformanceCalculator();
    }

    // ── computePercentage ────────────────────────────────────────────────────

    @Test
    @DisplayName("computePercentage: 10% gain when current = 110, previous = 100")
    void computePercentage_gain() {
        BigDecimal result = calculator.computePercentage(
                new BigDecimal("110"), new BigDecimal("100"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("10.0000000000"));
    }

    @Test
    @DisplayName("computePercentage: -5% loss when current = 95, previous = 100")
    void computePercentage_loss() {
        BigDecimal result = calculator.computePercentage(
                new BigDecimal("95"), new BigDecimal("100"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("-5.0000000000"));
    }

    @Test
    @DisplayName("computePercentage: 0% when current equals previous")
    void computePercentage_unchanged() {
        BigDecimal result = calculator.computePercentage(
                new BigDecimal("100"), new BigDecimal("100"));
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("computePercentage: returns 0 when previous is zero (guard)")
    void computePercentage_zeroPrevious() {
        BigDecimal result = calculator.computePercentage(
                new BigDecimal("100"), BigDecimal.ZERO);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── calculate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("calculate throws on null prices")
    void calculate_throwsOnNullPrices() {
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.calculate(null, 7));
    }

    @Test
    @DisplayName("calculate throws on non-positive interval")
    void calculate_throwsOnNonPositiveInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(), 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(List.of(), -1));
    }

    @Test
    @DisplayName("calculate produces correct record count for interval=1 and 5 prices")
    void calculate_recordCount() {
        List<StockPrice> prices = buildPrices("AAPL", 5, new BigDecimal("100"));
        List<PerformanceRecord> records = calculator.calculate(prices, 1);
        // 5 prices → 4 performance records (first price has no predecessor)
        assertThat(records).hasSize(4);
    }

    @Test
    @DisplayName("calculate computes correct percentage with interval=3")
    void calculate_correctValueInterval3() {
        // Prices: 100, 200, 300, 400, 500
        // At index 3 (price=400), previous at index 0 (price=100): perf = (400-100)/100 * 100 = 300%
        List<StockPrice> prices = new ArrayList<>();
        LocalDate base = LocalDate.of(2025, 1, 2);
        BigDecimal[] closes = {
                new BigDecimal("100"), new BigDecimal("200"),
                new BigDecimal("300"), new BigDecimal("400"),
                new BigDecimal("500")
        };
        for (int i = 0; i < closes.length; i++) {
            prices.add(new StockPrice("AAPL", base.plusDays(i), closes[i]));
        }

        List<PerformanceRecord> records = calculator.calculate(prices, 3);

        assertThat(records).hasSize(2);

        // index 3 vs 0 → (400-100)/100 * 100 = 300.0000
        PerformanceRecord first = records.get(0);
        assertThat(first.getSymbol()).isEqualTo("AAPL");
        assertThat(first.getDate()).isEqualTo(base.plusDays(3));
        assertThat(first.getPerformance()).isEqualByComparingTo(new BigDecimal("300.0000"));

        // index 4 vs 1 → (500-200)/200 * 100 = 150.0000
        PerformanceRecord second = records.get(1);
        assertThat(second.getPerformance()).isEqualByComparingTo(new BigDecimal("150.0000"));
    }

    @Test
    @DisplayName("calculate skips symbol with insufficient records")
    void calculate_skipsSymbolWithFewRecords() {
        // AAPL: 3 prices, interval=5 → should be skipped
        List<StockPrice> prices = buildPrices("AAPL", 3, new BigDecimal("100"));
        List<PerformanceRecord> records = calculator.calculate(prices, 5);
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("calculate handles multiple symbols independently")
    void calculate_multipleSymbols() {
        List<StockPrice> prices = new ArrayList<>();
        prices.addAll(buildPrices("AAPL", 5, new BigDecimal("100")));
        prices.addAll(buildPrices("MSFT", 5, new BigDecimal("200")));

        List<PerformanceRecord> records = calculator.calculate(prices, 1);

        long aaplCount = records.stream().filter(r -> r.getSymbol().equals("AAPL")).count();
        long msftCount = records.stream().filter(r -> r.getSymbol().equals("MSFT")).count();

        assertThat(aaplCount).isEqualTo(4);
        assertThat(msftCount).isEqualTo(4);
    }

    @Test
    @DisplayName("calculate result is sorted by date then symbol")
    void calculate_resultSorted() {
        List<StockPrice> prices = new ArrayList<>();
        LocalDate base = LocalDate.of(2025, 1, 2);
        // Two symbols with overlapping dates
        for (int i = 0; i < 3; i++) {
            prices.add(new StockPrice("MSFT", base.plusDays(i), new BigDecimal("300").add(BigDecimal.valueOf(i))));
            prices.add(new StockPrice("AAPL", base.plusDays(i), new BigDecimal("100").add(BigDecimal.valueOf(i))));
        }

        List<PerformanceRecord> records = calculator.calculate(prices, 1);

        // Verify sorted by date then symbol
        for (int i = 0; i < records.size() - 1; i++) {
            PerformanceRecord a = records.get(i);
            PerformanceRecord b = records.get(i + 1);
            int dateCmp = a.getDate().compareTo(b.getDate());
            assertThat(dateCmp).isLessThanOrEqualTo(0);
            if (dateCmp == 0) {
                assertThat(a.getSymbol().compareTo(b.getSymbol())).isLessThanOrEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("calculate returns immutable list")
    void calculate_returnsImmutableList() {
        List<StockPrice> prices = buildPrices("AAPL", 3, new BigDecimal("100"));
        List<PerformanceRecord> result = calculator.calculate(prices, 1);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> result.add(null));
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /**
     * Builds a list of {@code count} sequential daily price records for the given
     * symbol, with each close price 1% higher than the previous one.
     */
    private List<StockPrice> buildPrices(String symbol, int count, BigDecimal startPrice) {
        List<StockPrice> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2025, 1, 2);
        BigDecimal price = startPrice;
        for (int i = 0; i < count; i++) {
            list.add(new StockPrice(symbol, base.plusDays(i), price));
            price = price.add(new BigDecimal("1.00"));
        }
        return list;
    }
}

