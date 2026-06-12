package com.project.service;

import com.project.model.PerformanceRecord;
import com.project.util.FileUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Writes a list of {@link PerformanceRecord} objects to a CSV file.
 *
 * <h2>Output format</h2>
 * <pre>
 *   date,symbol,performance
 *   2025-01-29,AAPL,2.8521
 *   …
 * </pre>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>Apache Commons CSV is used consistently (same library as reading) to
 *       ensure correct quoting and escaping.</li>
 *   <li>UTF-8 BOM is intentionally omitted — standard CSV that Excel can open
 *       without issues on most locales.</li>
 *   <li>Parent directories are created automatically so callers don't need to
 *       pre-create the output folder.</li>
 * </ul>
 */
public class CsvWriterService {

    private static final Logger log = LoggerFactory.getLogger(CsvWriterService.class);

    /** Column headers exactly matching the required output format. */
    static final String[] HEADERS = {"date", "symbol", "performance"};

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader(HEADERS)
            .build();

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Writes {@code records} to {@code outputPath}, creating parent directories if
     * they do not already exist.  Existing files are silently overwritten.
     *
     * @param records    list of performance records to write (must not be null)
     * @param outputPath destination file path (must not be null)
     * @throws IOException if writing fails
     */
    public void write(List<PerformanceRecord> records, Path outputPath) throws IOException {
        Objects.requireNonNull(records,    "records must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        FileUtils.ensureParentExists(outputPath);

        log.info("Writing {} records to {}", records.size(), outputPath.toAbsolutePath());

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSV_FORMAT)) {

            for (PerformanceRecord rec : records) {
                printer.printRecord(
                        rec.getDate().toString(),           // ISO yyyy-MM-dd
                        rec.getSymbol(),
                        rec.getFormattedPerformance()       // plain decimal, no trailing %
                );
            }
        }

        log.info("Successfully wrote {}", outputPath.getFileName());
    }

    /**
     * Convenience factory method: builds the canonical output filename for a given interval.
     * E.g. interval=7 → "7day.csv"
     *
     * @param interval positive trading-day interval
     * @param outputDir directory where the file should reside
     * @return fully-qualified {@link Path}
     */
    public static Path outputPath(int interval, Path outputDir) {
        return outputDir.resolve(interval + "day.csv");
    }
}

