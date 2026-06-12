package com.project.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the model classes {@link StockPrice} and {@link PerformanceRecord}.
 */
@DisplayName("Model classes")
class ModelTest {

    // ── StockPrice ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("StockPrice constructor normalises symbol to upper case")
    void stockPrice_uppercasesSymbol() {
        StockPrice sp = new StockPrice("aapl", LocalDate.of(2025, 1, 2), new BigDecimal("100"));
        assertThat(sp.getSymbol()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("StockPrice rejects null symbol")
    void stockPrice_rejectsNullSymbol() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StockPrice(null, LocalDate.now(), BigDecimal.ONE));
    }

    @Test
    @DisplayName("StockPrice rejects blank symbol")
    void stockPrice_rejectsBlankSymbol() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StockPrice("  ", LocalDate.now(), BigDecimal.ONE));
    }

    @Test
    @DisplayName("StockPrice rejects null date")
    void stockPrice_rejectsNullDate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StockPrice("AAPL", null, BigDecimal.ONE));
    }

    @Test
    @DisplayName("StockPrice rejects zero close price")
    void stockPrice_rejectsZeroPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StockPrice("AAPL", LocalDate.now(), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("StockPrice rejects negative close price")
    void stockPrice_rejectsNegativePrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StockPrice("AAPL", LocalDate.now(), new BigDecimal("-1")));
    }

    @Test
    @DisplayName("StockPrice equals is based on symbol + date only")
    void stockPrice_equalsOnSymbolAndDate() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        StockPrice a = new StockPrice("AAPL", d, new BigDecimal("100"));
        StockPrice b = new StockPrice("AAPL", d, new BigDecimal("200")); // different price
        assertThat(a).isEqualTo(b);
    }

    // ── PerformanceRecord ────────────────────────────────────────────────────

    @Test
    @DisplayName("PerformanceRecord rounds performance to 4 decimal places")
    void performanceRecord_roundsTo4dp() {
        PerformanceRecord pr = new PerformanceRecord(
                LocalDate.of(2025, 1, 9), "AAPL", new BigDecimal("2.85219999"), 7);
        assertThat(pr.getPerformance().scale()).isEqualTo(4);
        assertThat(pr.getPerformance()).isEqualByComparingTo(new BigDecimal("2.8522"));
    }

    @Test
    @DisplayName("PerformanceRecord getFormattedPerformance returns plain decimal string")
    void performanceRecord_formattedPerformance() {
        PerformanceRecord pr = new PerformanceRecord(
                LocalDate.of(2025, 1, 9), "GOOG", new BigDecimal("-1.2500"), 7);
        assertThat(pr.getFormattedPerformance()).isEqualTo("-1.2500");
    }

    @Test
    @DisplayName("PerformanceRecord rejects non-positive interval")
    void performanceRecord_rejectsNonPositiveInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PerformanceRecord(
                        LocalDate.now(), "AAPL", BigDecimal.ONE, 0));
    }

    @Test
    @DisplayName("PerformanceRecord rejects null symbol")
    void performanceRecord_rejectsNullSymbol() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PerformanceRecord(
                        LocalDate.now(), null, BigDecimal.ONE, 7));
    }
}

