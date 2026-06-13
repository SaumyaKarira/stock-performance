package com.project.service;

import com.project.model.MonthlyAverage;
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
 * Writes a list of {@link MonthlyAverage} objects to a CSV file.
 *
 * <h2>Output format</h2>
 * <pre>
 *   symbol,month,average_price
 *   AAPL,JAN,222.77
 *   AAPL,FEB,229.61
 *   …
 * </pre>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>Apache Commons CSV is used for correct RFC-4180 quoting/escaping,
 *       consistent with the rest of the project.</li>
 *   <li>Parent directories are created automatically.</li>
 *   <li>UTF-8 without BOM – universally readable in spreadsheets and scripts.</li>
 * </ul>
 */
public class MonthlyAverageCsvWriter {

    private static final Logger log = LoggerFactory.getLogger(MonthlyAverageCsvWriter.class);

    /** Column headers matching the required output specification. */
    static final String[] HEADERS = {"symbol", "month", "average_price"};

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader(HEADERS)
            .build();

    /**
     * Writes {@code averages} to {@code outputPath}.
     * Existing files are silently overwritten.
     *
     * @param averages   list of monthly averages to write (must not be null)
     * @param outputPath destination file path (must not be null)
     * @throws IOException if writing fails
     */
    public void write(List<MonthlyAverage> averages, Path outputPath) throws IOException {
        Objects.requireNonNull(averages,    "averages must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        FileUtils.ensureParentExists(outputPath);

        log.info("Writing {} monthly-average rows to {}",
                 averages.size(), outputPath.toAbsolutePath());

        try (BufferedWriter bw      = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
             CSVPrinter     printer = new CSVPrinter(bw, CSV_FORMAT)) {

            for (MonthlyAverage ma : averages) {
                printer.printRecord(
                        ma.symbol(),
                        ma.month(),
                        ma.averagePrice().toPlainString()
                );
            }
        }

        log.info("Successfully wrote {}", outputPath.getFileName());
    }
}

