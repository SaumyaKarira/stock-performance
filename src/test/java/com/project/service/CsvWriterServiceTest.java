package com.project.service;

import com.project.model.PerformanceRecord;
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

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CsvWriterService}.
 */
@DisplayName("CsvWriterService")
class CsvWriterServiceTest {

    private CsvWriterService writer;

    @BeforeEach
    void setUp() {
        writer = new CsvWriterService();
    }

    // ── write ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("write creates file with correct header and records")
    void write_createsFileWithHeaderAndRecords(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("7day.csv");

        List<PerformanceRecord> records = List.of(
                new PerformanceRecord(LocalDate.of(2025, 1, 9), "AAPL", new BigDecimal("2.8521"), 7),
                new PerformanceRecord(LocalDate.of(2025, 1, 9), "GOOG", new BigDecimal("-1.2345"), 7)
        );

        writer.write(records, out);

        assertThat(Files.exists(out)).isTrue();
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);

        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lines.get(0)).isEqualTo("date,symbol,performance");
        assertThat(lines.get(1)).isEqualTo("2025-01-09,AAPL,2.8521");
        assertThat(lines.get(2)).isEqualTo("2025-01-09,GOOG,-1.2345");
    }

    @Test
    @DisplayName("write creates parent directories if they don't exist")
    void write_createsParentDirectories(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("sub/dir/7day.csv");

        List<PerformanceRecord> records = List.of(
                new PerformanceRecord(LocalDate.of(2025, 1, 9), "MSFT", new BigDecimal("0.5000"), 7)
        );

        writer.write(records, out);
        assertThat(Files.exists(out)).isTrue();
    }

    @Test
    @DisplayName("write with empty list produces only header")
    void write_emptyListProducesOnlyHeader(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("empty.csv");
        writer.write(List.of(), out);

        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        // header only (trailing newline may add one empty line)
        assertThat(lines.get(0)).isEqualTo("date,symbol,performance");
    }

    @Test
    @DisplayName("write throws NullPointerException for null records")
    void write_throwsOnNullRecords(@TempDir Path tmp) {
        assertThatNullPointerException()
                .isThrownBy(() -> writer.write(null, tmp.resolve("out.csv")));
    }

    @Test
    @DisplayName("write throws NullPointerException for null path")
    void write_throwsOnNullPath() {
        assertThatNullPointerException()
                .isThrownBy(() -> writer.write(List.of(), null));
    }

    // ── outputPath ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("outputPath returns correct filename for given interval")
    void outputPath_correctFilename(@TempDir Path dir) {
        assertThat(CsvWriterService.outputPath(7, dir).getFileName().toString())
                .isEqualTo("7day.csv");
        assertThat(CsvWriterService.outputPath(14, dir).getFileName().toString())
                .isEqualTo("14day.csv");
        assertThat(CsvWriterService.outputPath(30, dir).getFileName().toString())
                .isEqualTo("30day.csv");
    }

    // ── round-trip ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("round-trip: written CSV can be read back with correct values")
    void roundTrip(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("rt.csv");

        List<PerformanceRecord> original = List.of(
                new PerformanceRecord(LocalDate.of(2025, 3, 15), "NVDA", new BigDecimal("12.3456"), 30)
        );

        writer.write(original, out);

        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        // Line 1 is header, line 2 is the data row
        String[] parts = lines.get(1).split(",");
        assertThat(parts[0]).isEqualTo("2025-03-15");
        assertThat(parts[1]).isEqualTo("NVDA");
        assertThat(new BigDecimal(parts[2])).isEqualByComparingTo(new BigDecimal("12.3456"));
    }
}

