package com.project.service;

import com.project.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DatabaseLoaderService}.
 *
 * <p>CSV parsing helpers ({@code parseIdentifiers}, {@code parsePrices},
 * {@code normaliseId}) are exercised using inline CSV fixtures written to a
 * {@code @TempDir}.  The {@link StockRepository} is mocked so no database
 * is required.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseLoaderService")
class DatabaseLoaderServiceTest {

    @Mock StockRepository repository;

    private DatabaseLoaderService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseLoaderService(repository);
    }

    // ── normaliseId ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("normaliseId strips commas from quoted CSV id (\"40,359,100\" → 40359100)")
    void normaliseId_stripsCommas() {
        assertThat(DatabaseLoaderService.normaliseId("40,359,100")).isEqualTo(40359100L);
        assertThat(DatabaseLoaderService.normaliseId("7,458")).isEqualTo(7458L);
        assertThat(DatabaseLoaderService.normaliseId("13")).isEqualTo(13L);
    }

    @Test
    @DisplayName("normaliseId trims surrounding whitespace")
    void normaliseId_trimsWhitespace() {
        assertThat(DatabaseLoaderService.normaliseId("  5,094  ")).isEqualTo(5094L);
    }

    // ── parseIdentifiers ──────────────────────────────────────────────────────

    @Test
    @DisplayName("parseIdentifiers correctly maps all rows from a valid CSV")
    void parseIdentifiers_parsesAllRows(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("stock_identifiers.csv");
        Files.writeString(csv, """
                "id_stock","name","symbol"
                "13","Apple Inc.","AAPL"
                "40,359,100","Alphabet Inc.","GOOG"
                "5,094","Microsoft Corporation","MSFT"
                """, StandardCharsets.UTF_8);

        List<StockRepository.IdentifierRow> rows = service.parseIdentifiers(csv);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).idStock()).isEqualTo(13L);
        assertThat(rows.get(0).symbol()).isEqualTo("AAPL");
        assertThat(rows.get(1).idStock()).isEqualTo(40359100L);
        assertThat(rows.get(1).symbol()).isEqualTo("GOOG");
        assertThat(rows.get(2).idStock()).isEqualTo(5094L);
    }

    @Test
    @DisplayName("parseIdentifiers upcases symbol")
    void parseIdentifiers_upcasesSymbol(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("ids.csv");
        Files.writeString(csv, """
                "id_stock","name","symbol"
                "13","Apple Inc.","aapl"
                """, StandardCharsets.UTF_8);

        List<StockRepository.IdentifierRow> rows = service.parseIdentifiers(csv);
        assertThat(rows.get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("parseIdentifiers skips rows with blank symbol")
    void parseIdentifiers_skipsBlankSymbol(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("ids.csv");
        Files.writeString(csv, """
                "id_stock","name","symbol"
                "99","Unknown","   "
                "13","Apple Inc.","AAPL"
                """, StandardCharsets.UTF_8);

        List<StockRepository.IdentifierRow> rows = service.parseIdentifiers(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).symbol()).isEqualTo("AAPL");
    }

    // ── parsePrices ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parsePrices parses all OHLC columns correctly")
    void parsePrices_parsesAllColumns(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("stock_prices.csv");
        Files.writeString(csv, """
                "id_stock","high","low","close","date"
                "13","229.98","219.38","222.64","2025-01-21"
                "7,458","6,894.87","6,824.31","6,858.47","2026-01-02"
                """, StandardCharsets.UTF_8);

        List<StockRepository.PriceRow> rows = service.parsePrices(csv);

        assertThat(rows).hasSize(2);

        StockRepository.PriceRow first = rows.get(0);
        assertThat(first.idStock()).isEqualTo(13L);
        assertThat(first.high()).isEqualByComparingTo(new BigDecimal("229.98"));
        assertThat(first.low()).isEqualByComparingTo(new BigDecimal("219.38"));
        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("222.64"));

        StockRepository.PriceRow second = rows.get(1);
        assertThat(second.idStock()).isEqualTo(7458L);
        assertThat(second.high()).isEqualByComparingTo(new BigDecimal("6894.87"));
    }

    @Test
    @DisplayName("parsePrices strips thousand-separator commas from price values")
    void parsePrices_stripsCommasFromPrices(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("prices.csv");
        Files.writeString(csv, """
                "id_stock","high","low","close","date"
                "7,458","6,977.27","6,934.07","6,966.28","2026-01-09"
                """, StandardCharsets.UTF_8);

        List<StockRepository.PriceRow> rows = service.parsePrices(csv);
        assertThat(rows.get(0).close()).isEqualByComparingTo(new BigDecimal("6966.28"));
    }

    @Test
    @DisplayName("parsePrices skips malformed rows without aborting")
    void parsePrices_skipsMalformedRows(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("prices.csv");
        Files.writeString(csv, """
                "id_stock","high","low","close","date"
                "13","229.98","219.38","INVALID","2025-01-21"
                "13","229.98","219.38","222.64","2025-01-22"
                """, StandardCharsets.UTF_8);

        List<StockRepository.PriceRow> rows = service.parsePrices(csv);
        assertThat(rows).hasSize(1);
    }
}



