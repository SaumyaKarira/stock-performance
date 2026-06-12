package com.project;

import com.project.model.PerformanceRecord;
import com.project.model.StockPrice;
import com.project.service.*;
import com.project.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar stock-performance.jar &lt;stock_prices.csv&gt; [interval]
 * </pre>
 *
 * <ul>
 *   <li>{@code stock_prices.csv} — path to the prices file (relative or absolute)</li>
 *   <li>{@code interval}         — optional positive integer (trading days).
 *       <ul>
 *         <li>If omitted → produces <b>7day.csv</b>, <b>14day.csv</b>, <b>30day.csv</b></li>
 *         <li>If provided → produces <b>only</b> {@code <interval>day.csv}</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>The identifiers file must be in the same directory as the prices file,
 * named {@code stock_identifiers.csv}.</p>
 *
 * <p>System.exit codes: 0 = success, 1 = bad arguments, 2 = IO / runtime error.</p>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** Default intervals produced when no interval argument is supplied. */
    static final int[] DEFAULT_INTERVALS = {7, 14, 30};

    static final String IDENTIFIERS_FILE = "stock_identifiers.csv";

    public static void main(String[] args) {
        // ── 1.  Parse and validate arguments ────────────────────────────────
        // Interval argument is now OPTIONAL.
        if (args.length < 1) {
            System.err.println("Usage: java -jar stock-performance.jar <stock_prices.csv> [interval]");
            System.err.println("  No interval  → produces 7day.csv, 14day.csv, 30day.csv");
            System.err.println("  With interval → produces only <interval>day.csv");
            System.err.println("Example: java -jar stock-performance.jar files/stock_prices.csv 7");
            System.exit(1);
        }

        Path pricesPath = FileUtils.resolve(args[0]);

        // Parse optional interval
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

        // ── 2.  Bootstrap services ───────────────────────────────────────────
        CsvReaderService      reader     = new CsvReaderService();
        PerformanceCalculator calculator = new PerformanceCalculator();
        CsvWriterService      writer     = new CsvWriterService();
        Path outputDir = FileUtils.resolve("output");

        try {
            // ── 3.  Read input data ──────────────────────────────────────────
            log.info("Reading input files …");
            log.info("  prices:      {}", pricesPath);
            log.info("  identifiers: {}", identifiersPath);
            List<StockPrice> prices = reader.read(identifiersPath, pricesPath);
            log.info("Loaded {} total price records across all symbols.", prices.size());

            // ── 4.  Determine intervals to process ───────────────────────────
            //   • No interval arg  → run all three defaults (7, 14, 30)
            //   • Interval provided → run only that one interval
            List<Integer> intervals = new ArrayList<>();
            if (cliInterval == null) {
                for (int i : DEFAULT_INTERVALS) intervals.add(i);
                log.info("No interval specified — producing 7day, 14day, 30day CSVs.");
            } else {
                intervals.add(cliInterval);
                log.info("Interval {} specified — producing only {}day.csv.", cliInterval, cliInterval);
            }

            // ── 5.  Calculate and write ──────────────────────────────────────
            List<Path> outputFiles = new ArrayList<>();

            for (int interval : intervals) {
                log.info("── Processing interval: {} trading days ──", interval);
                List<PerformanceRecord> records = calculator.calculate(prices, interval);
                Path outFile = CsvWriterService.outputPath(interval, outputDir);
                writer.write(records, outFile);
                outputFiles.add(outFile);
            }

            log.info("All output files written to: {}", outputDir.toAbsolutePath());

            // ── 6.  Send email (best-effort, opt-in) ────────────────────────
            String emailTo = System.getenv("EMAIL_TO");
            if (emailTo != null && !emailTo.isBlank()) {
                sendEmail(outputFiles);
            } else {
                log.info("EMAIL_TO not set — skipping email. Set EMAIL_TO=<address> to enable.");
            }

        } catch (Exception e) {
            log.error("Fatal error during processing: {}", e.getMessage(), e);
            System.exit(2);
        }

        log.info("Done.");
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static void sendEmail(List<Path> files) {
        try {
            EmailService.EmailConfig cfg = EmailService.EmailConfig.fromEnv();
            EmailService emailService = new EmailService(cfg);
            emailService.send(files);
        } catch (Throwable e) {
            // Email is best-effort: log the failure but do NOT abort the run.
            // We catch Throwable (not just Exception) to gracefully handle
            // NoClassDefFoundError when e.g. angus-mail is absent at runtime.
            log.warn("Email sending failed (non-fatal): {}", e.getMessage());
            log.debug("Email error detail:", e);
        }
    }
}



