package com.project.service;

import com.project.model.StockPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CsvReaderService}.
 *
 * <p>Uses JUnit 5's {@code @TempDir} to write small inline CSV fixtures rather
 * than hard-coded test resource paths, making the tests self-contained and
 * independent of the file system layout.</p>
 */
@DisplayName("CsvReaderService")
class CsvReaderServiceTest {

    private CsvReaderService service;

    @BeforeEach
    void setUp() {
        service = new CsvReaderService();
    }

    // ── normaliseId ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("normaliseId strips commas from quoted CSV numbers")
    void normaliseId_stripsCommas() {
        assertThat(CsvReaderService.normaliseId("40,359,100")).isEqualTo("40359100");
        assertThat(CsvReaderService.normaliseId("7,458")).isEqualTo("7458");
        assertThat(CsvReaderService.normaliseId("13")).isEqualTo("13");
    }

    @Test
    @DisplayName("normaliseId trims surrounding whitespace")
    void normaliseId_trimWhitespace() {
        assertThat(CsvReaderService.normaliseId("  13  ")).isEqualTo("13");
    }

    // ── readIdentifiers ──────────────────────────────────────────────────────

    @Test
    @DisplayName("readIdentifiers correctly maps id_stock to symbol")
    void readIdentifiers_mapsIdToSymbol(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("ids.csv");
        Files.writeString(csv, """
                "id_stock","name","symbol"
                "13","Apple Inc.","AAPL"
                "40,359,100","Alphabet Inc.","GOOG"
                """, StandardCharsets.UTF_8);

        Map<String, String> map = service.readIdentifiers(csv);

        assertThat(map).containsEntry("13", "AAPL");
        assertThat(map).containsEntry("40359100", "GOOG");
    }

    @Test
    @DisplayName("readIdentifiers skips rows with empty symbol")
    void readIdentifiers_skipsEmptySymbol(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("ids.csv");
        Files.writeString(csv, """
                "id_stock","name","symbol"
                "99","Unknown","  "
                "13","Apple Inc.","AAPL"
                """, StandardCharsets.UTF_8);

        Map<String, String> map = service.readIdentifiers(csv);

        assertThat(map).hasSize(1).containsEntry("13", "AAPL");
    }

    // ── read (full pipeline) ─────────────────────────────────────────────────

    @Test
    @DisplayName("read returns sorted price records for all known symbols")
    void read_returnsSortedRecords(@TempDir Path tmp) throws IOException {
        Path ids = tmp.resolve("stock_identifiers.csv");
        Path prices = tmp.resolve("stock_prices.csv");

        Files.writeString(ids, """
                "id_stock","name","symbol"
                "13","Apple Inc.","AAPL"
                """, StandardCharsets.UTF_8);

        Files.writeString(prices, """
                "id_stock","high","low","close","date"
                "13","105.00","95.00","100.00","2025-01-03"
                "13","100.00","90.00","95.00","2025-01-02"
                """, StandardCharsets.UTF_8);

        List<StockPrice> result = service.read(ids, prices);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(result.get(1).getDate()).isEqualTo(LocalDate.of(2025, 1, 3));
    }

    @Test
    @DisplayName("read returns immutable list")
    void read_returnsImmutableList(@TempDir Path tmp) throws IOException {
        Path ids = tmp.resolve("stock_identifiers.csv");
        Path prices = tmp.resolve("stock_prices.csv");

        Files.writeString(ids, """
                "id_stock","name","symbol"
                "13","Apple Inc.","AAPL"
                """, StandardCharsets.UTF_8);

        Files.writeString(prices, """
                "id_stock","high","low","close","date"
                "13","100.00","90.00","100.00","2025-01-02"
                """, StandardCharsets.UTF_8);

        List<StockPrice> result = service.read(ids, prices);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> result.add(null));
    }

    @Test
    @DisplayName("read skips rows with unknown id_stock")
    void read_skipsUnknownId(@TempDir Path tmp) throws IOException {
        Path ids = tmp.resolve("stock_identifiers.csv");
        Path prices = tmp.resolve("stock_prices.csv");

        Files.writeString(ids, """
                "id_stock","name","symbol"
                "13","Apple Inc.","AAPL"
                """, StandardCharsets.UTF_8);

        // Row with id 999 does not exist in identifiers
        Files.writeString(prices, """
                "id_stock","high","low","close","date"
                "13","100.00","90.00","100.00","2025-01-02"
                "999","50.00","40.00","45.00","2025-01-02"
                """, StandardCharsets.UTF_8);

        List<StockPrice> result = service.read(ids, prices);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("read throws IllegalArgumentException when no valid records exist")
    void read_throwsWhenEmpty(@TempDir Path tmp) throws IOException {
        Path ids = tmp.resolve("stock_identifiers.csv");
        Path prices = tmp.resolve("stock_prices.csv");

        Files.writeString(ids, """
                "id_stock","name","symbol"
                """, StandardCharsets.UTF_8);

        Files.writeString(prices, """
                "id_stock","high","low","close","date"
                "13","100.00","90.00","100.00","2025-01-02"
                """, StandardCharsets.UTF_8);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.read(ids, prices));
    }

    @Test
    @DisplayName("read correctly parses quoted id_stock with commas (e.g. '7,458')")
    void read_handlesQuotedIdWithCommas(@TempDir Path tmp) throws IOException {
        Path ids = tmp.resolve("stock_identifiers.csv");
        Path prices = tmp.resolve("stock_prices.csv");

        Files.writeString(ids, """
                "id_stock","name","symbol"
                "7,458","S&P 500 Index","SPX"
                """, StandardCharsets.UTF_8);

        Files.writeString(prices, """
                "id_stock","high","low","close","date"
                "7,458","5000.00","4900.00","4950.00","2025-01-02"
                """, StandardCharsets.UTF_8);

        List<StockPrice> result = service.read(ids, prices);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo("SPX");
        assertThat(result.get(0).getClosePrice()).isEqualByComparingTo(new BigDecimal("4950.00"));
    }
}

