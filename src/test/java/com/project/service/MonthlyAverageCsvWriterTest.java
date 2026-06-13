package com.project.service;

import com.project.model.MonthlyAverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Unit tests for {@link MonthlyAverageCsvWriter}. */
@DisplayName("MonthlyAverageCsvWriter")
class MonthlyAverageCsvWriterTest {

    private MonthlyAverageCsvWriter writer;

    @BeforeEach
    void setUp() {
        writer = new MonthlyAverageCsvWriter();
    }

    @Test
    @DisplayName("write produces correct header and data rows")
    void write_correctContent(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("monthly_average_prices.csv");
        List<MonthlyAverage> avgs = List.of(
            new MonthlyAverage("AAPL", "JAN", new BigDecimal("222.77")),
            new MonthlyAverage("GOOG", "JAN", new BigDecimal("185.34"))
        );
        writer.write(avgs, out);
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("symbol,month,average_price");
        assertThat(lines.get(1)).isEqualTo("AAPL,JAN,222.77");
        assertThat(lines.get(2)).isEqualTo("GOOG,JAN,185.34");
        assertThat(lines).hasSize(3);
    }

    @Test
    @DisplayName("write creates parent directories automatically")
    void write_createsParentDirs(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("sub/dir/out.csv");
        writer.write(List.of(new MonthlyAverage("MSFT", "MAR", new BigDecimal("410.00"))), nested);
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    @DisplayName("write produces header-only file for empty list")
    void write_emptyList_headerOnly(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out.csv");
        writer.write(List.of(), out);
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("symbol,month,average_price");
    }

    @Test
    @DisplayName("write rejects null averages")
    void write_nullAverages_throws(@TempDir Path tmp) {
        assertThatNullPointerException()
            .isThrownBy(() -> writer.write(null, tmp.resolve("out.csv")));
    }

    @Test
    @DisplayName("HEADERS constant matches specification")
    void headers_matchSpec() {
        assertThat(MonthlyAverageCsvWriter.HEADERS)
            .containsExactly("symbol", "month", "average_price");
    }
}

