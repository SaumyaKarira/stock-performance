package com.project.service;

import com.project.model.MonthlyAverage;
import com.project.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Orchestrates Part 2 pipeline: DB query -> CSV write -> email (best-effort). */
public class MonthlyAverageService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyAverageService.class);

    static final String OUTPUT_FILE = "monthly_average_prices.csv";

    private final StockRepository         repository;
    private final MonthlyAverageCsvWriter writer;

    public MonthlyAverageService(StockRepository repository, MonthlyAverageCsvWriter writer) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.writer     = Objects.requireNonNull(writer,     "writer must not be null");
    }

    public Path generateAndSend(Path outputDir) throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");

        log.info("Querying monthly average closing prices from database...");
        List<MonthlyAverage> averages = repository.getMonthlyAverages();

        if (averages.isEmpty()) {
            log.warn("Monthly-average query returned no data. Ensure CSVs loaded.");
        }

        Path csvFile = outputDir.resolve(OUTPUT_FILE);
        writer.write(averages, csvFile);
        log.info("Monthly averages written to: {}", csvFile.toAbsolutePath());

        String emailTo = System.getenv("EMAIL_TO");
        if (emailTo != null && !emailTo.isBlank()) {
            sendEmail(csvFile);
        } else {
            log.info("EMAIL_TO not configured - skipping email.");
        }

        return csvFile;
    }

    private void sendEmail(Path csvFile) {
        try {
            EmailService.EmailConfig cfg = EmailService.EmailConfig.fromEnv();
            new EmailService(cfg).send(List.of(csvFile));
            log.info("Monthly average report emailed successfully.");
        } catch (Throwable t) {
            log.warn("Email sending failed (non-fatal): {}", t.getMessage());
            log.debug("Email error detail:", t);
        }
    }
}
