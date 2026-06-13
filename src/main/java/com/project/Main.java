package com.project;

import com.project.config.DatabaseConfig;
import com.project.model.PerformanceRecord;
import com.project.model.StockPrice;
import com.project.repository.StockRepository;
import com.project.service.*;
import com.project.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point — supports two modes:
 *
 * <h2>Mode 1: CSV (Part 1 – original behaviour)</h2>
 * <pre>
 *   java -jar stock-performance.jar &lt;stock_prices.csv&gt; [interval]
 * </pre>
 * Reads price CSVs, calculates N-day performance, writes Nday.csv, emails results.
 *
 * <h2>Mode 2: DB (Part 2 – database mode)</h2>
 * <pre>
 *   java -jar stock-performance.jar db
 * </pre>
 * Loads stock_identifiers.csv + stock_prices.csv into MySQL, computes monthly
 * average closing prices via SQL aggregation, writes monthly_average_prices.csv,
 * and emails the result.
 *
 * <p>System.exit codes: 0 = success, 1 = bad arguments, 2 = IO / runtime error.</p>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    static final int[] DEFAULT_INTERVALS = {7, 14, 30};
    static final String IDENTIFIERS_FILE = "stock_identifiers.csv";

    public static void main(String[] args) {
        // ── Route to the correct mode ────────────────────────────────────────
        if (args.length >= 1 && "db".equalsIgnoreCase(args[0])) {
            runDatabaseMode();
        } else {
            runCsvMode(args);
        }
    }

    // ── Mode 2: Database ─────────────────────────────────────────────────────

    /**
     * Part 2 pipeline:
     * <ol>
     *   <li>Build a HikariCP DataSource from environment variables</li>
     *   <li>Load stock_identifiers.csv and stock_prices.csv into MySQL (idempotent)</li>
     *   <li>Query monthly average closing prices via SQL aggregation</li>
     *   <li>Write monthly_average_prices.csv to the output directory</li>
     *   <li>Email the CSV (best-effort)</li>
     * </ol>
     */
    private static void runDatabaseMode() {
        log.info("=== Stock Performance Calculator – Database Mode (Part 2) ===");
        try {
            DataSource       ds         = DatabaseConfig.createDataSource();
            StockRepository  repo       = new StockRepository(ds);
            DatabaseLoaderService loader = new DatabaseLoaderService(repo);
            MonthlyAverageService svc   = new MonthlyAverageService(repo, new MonthlyAverageCsvWriter());

            Path filesDir         = FileUtils.resolve("files");
            Path identifiersPath  = filesDir.resolve(IDENTIFIERS_FILE);
            Path pricesPath       = filesDir.resolve("stock_prices.csv");
            Path outputDir        = FileUtils.resolve("output");

            // Step 1: Load CSV data into MySQL (skipped if tables already have data)
            loader.load(identifiersPath, pricesPath);

            // Step 2: Compute monthly averages + write CSV + email
            Path csvFile = svc.generateAndSend(outputDir);
            log.info("Generated: {}", csvFile.toAbsolutePath());

            log.info("=== Database Mode Complete ===");

        } catch (Exception e) {
            log.error("Fatal error in database mode: {}", e.getMessage(), e);
            System.exit(2);
        }
    }

    // ── Mode 1: CSV (original Part 1 behaviour) ───────────────────────────────

    private static void runCsvMode(String[] args) {
        log.info("=== Stock Performance Calculator – CSV Mode (Part 1) ===");

        if (args.length < 1) {
            System.err.println("Usage: java -jar stock-performance.jar <stock_prices.csv> [interval]");
            System.err.println("       java -jar stock-performance.jar db                  (Part 2 DB mode)");
            System.exit(1);
        }

        Path pricesPath = FileUtils.resolve(args[0]);

        Integer cliInterval = null;
        if (args.length >= 2) {
            try {
                cliInterval = Integer.parseInt(args[1]);
                if (cliInterval <= 0) throw new NumberFormatException("must be positive");
            } catch (NumberFormatException e) {
                System.err.println("Error: <interval> must be a positive integer, got: " + args[1]);
                System.exit(1);
            }
        }

        if (!Files.exists(pricesPath)) {
            System.err.println("Error: prices file not found: " + pricesPath);
            System.exit(1);
        }

        Path identifiersPath = pricesPath.resolveSibling(IDENTIFIERS_FILE);
        if (!Files.exists(identifiersPath)) {
            System.err.println("Error: identifiers file not found: " + identifiersPath);
            System.exit(1);
        }

        CsvReaderService      reader     = new CsvReaderService();
        PerformanceCalculator calculator = new PerformanceCalculator();
        CsvWriterService      writer     = new CsvWriterService();
        Path outputDir = FileUtils.resolve("output");

        try {
            log.info("Reading input files …");
            List<StockPrice> prices = reader.read(identifiersPath, pricesPath);
            log.info("Loaded {} total price records across all symbols.", prices.size());

            List<Integer> intervals = new ArrayList<>();
            if (cliInterval == null) {
                for (int i : DEFAULT_INTERVALS) intervals.add(i);
                log.info("No interval specified — producing 7day, 14day, 30day CSVs.");
            } else {
                intervals.add(cliInterval);
                log.info("Interval {} specified — producing only {}day.csv.", cliInterval, cliInterval);
            }

            List<Path> outputFiles = new ArrayList<>();
            for (int interval : intervals) {
                log.info("── Processing interval: {} trading days ──", interval);
                List<PerformanceRecord> records = calculator.calculate(prices, interval);
                Path outFile = CsvWriterService.outputPath(interval, outputDir);
                writer.write(records, outFile);
                outputFiles.add(outFile);
            }

            log.info("All output files written to: {}", outputDir.toAbsolutePath());

            String emailTo = System.getenv("EMAIL_TO");
            if (emailTo != null && !emailTo.isBlank()) {
                sendEmail(outputFiles);
            } else {
                log.info("EMAIL_TO not set — skipping email.");
            }

        } catch (Exception e) {
            log.error("Fatal error during processing: {}", e.getMessage(), e);
            System.exit(2);
        }

        log.info("Done.");
    }

    private static void sendEmail(List<Path> files) {
        try {
            EmailService.EmailConfig cfg = EmailService.EmailConfig.fromEnv();
            EmailService emailService = new EmailService(cfg);
            emailService.send(files);
        } catch (Throwable e) {
            log.warn("Email sending failed (non-fatal): {}", e.getMessage());
            log.debug("Email error detail:", e);
        }
    }
}



