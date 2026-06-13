package com.project.service;

import com.project.model.MonthlyAverage;
import com.project.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MonthlyAverageService}.
 *
 * <p>The {@link StockRepository} and {@link MonthlyAverageCsvWriter} are mocked
 * so tests verify orchestration logic without touching the database or filesystem
 * (except for the @TempDir cases that verify CSV content).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyAverageService")
class MonthlyAverageServiceTest {

    @Mock StockRepository         repository;
    @Mock MonthlyAverageCsvWriter writer;

    private MonthlyAverageService service;

    @BeforeEach
    void setUp() {
        service = new MonthlyAverageService(repository, writer);
    }

    // ── constructor ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("constructor rejects null repository")
    void constructor_rejectsNullRepository() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MonthlyAverageService(null, writer))
                .withMessageContaining("repository");
    }

    @Test
    @DisplayName("constructor rejects null writer")
    void constructor_rejectsNullWriter() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MonthlyAverageService(repository, null))
                .withMessageContaining("writer");
    }

    // ── generateAndSend ───────────────────────────────────────────────────────

    @Test
    @DisplayName("generateAndSend queries the repository and passes results to writer")
    void generateAndSend_queriesAndWrites(@TempDir Path outputDir) throws Exception {
        List<MonthlyAverage> averages = List.of(
                new MonthlyAverage("AAPL", "JAN", new BigDecimal("222.77")),
                new MonthlyAverage("AAPL", "FEB", new BigDecimal("229.61"))
        );
        when(repository.getMonthlyAverages()).thenReturn(averages);

        Path result = service.generateAndSend(outputDir);

        verify(repository, times(1)).getMonthlyAverages();
        verify(writer, times(1)).write(eq(averages), eq(result));
        assertThat(result.getFileName().toString()).isEqualTo("monthly_average_prices.csv");
    }

    @Test
    @DisplayName("generateAndSend returns correct output path inside the given directory")
    void generateAndSend_returnsCorrectPath(@TempDir Path outputDir) throws Exception {
        when(repository.getMonthlyAverages()).thenReturn(List.of());

        Path result = service.generateAndSend(outputDir);

        assertThat(result).isEqualTo(outputDir.resolve("monthly_average_prices.csv"));
    }

    @Test
    @DisplayName("generateAndSend does not throw when query returns empty list")
    void generateAndSend_toleratesEmptyResults(@TempDir Path outputDir) throws Exception {
        when(repository.getMonthlyAverages()).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.generateAndSend(outputDir));
        verify(writer).write(eq(List.of()), any(Path.class));
    }

    @Test
    @DisplayName("generateAndSend rejects null outputDir")
    void generateAndSend_rejectsNullOutputDir() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.generateAndSend(null));
    }

    // ── OUTPUT_FILE constant ──────────────────────────────────────────────────

    @Test
    @DisplayName("OUTPUT_FILE constant matches specification")
    void outputFileConstant_matchesSpec() {
        assertThat(MonthlyAverageService.OUTPUT_FILE).isEqualTo("monthly_average_prices.csv");
    }
}

