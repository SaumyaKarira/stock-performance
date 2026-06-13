package com.project.service;

import com.project.repository.StockRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the stock CSV files and loads their contents into the MySQL database.
 *
 * <h2>Why Java-based loading instead of LOAD DATA INFILE?</h2>
 * <ul>
 *   <li>The {@code id_stock} column uses quoted values with embedded thousand-
 *       separator commas (e.g. {@code "40,359,100"}) that must be normalised to
 *       {@code 40359100} before insertion.  MySQL's {@code LOAD DATA INFILE}
 *       cannot perform this transformation without complex {@code @var} tricks.</li>
 *   <li>Price columns also use quoted comma-formatted numbers in some rows
 *       (e.g. {@code "6,894.87"}).</li>
 *   <li>MySQL's {@code secure_file_priv} requires extra Docker configuration to
 *       allow file access inside the container.</li>
 *   <li>Reusing Apache Commons CSV (already on the classpath) keeps the parsing
 *       logic consistent, tested, and portable.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Before inserting, the loader checks whether each table already contains data.
 * Running {@code docker compose up} multiple times therefore produces no
 * duplicates.  The underlying {@code INSERT IGNORE} provides a second line of
 * defence at the database level.
 */
public class DatabaseLoaderService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseLoaderService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Shared CSV format matching both input files (RFC-4180, first row = header). */
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    private final StockRepository repository;

    public DatabaseLoaderService(StockRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Loads both CSV files into the database.
     * Identifiers are loaded first (required by the FK constraint on prices).
     * Each table is skipped if it already contains data.
     *
     * @param identifiersPath path to {@code stock_identifiers.csv}
     * @param pricesPath      path to {@code stock_prices.csv}
     * @throws Exception if a CSV parse or database error occurs
     */
    public void load(Path identifiersPath, Path pricesPath) throws Exception {
        log.info("=== CSV → Database Load ===");

        // ── stock_identifiers ─────────────────────────────────────────────────
        if (repository.hasIdentifiers()) {
            log.info("stock_identifiers already populated – skipping.");
        } else {
            List<StockRepository.IdentifierRow> ids = parseIdentifiers(identifiersPath);
            int inserted = repository.insertIdentifiers(ids);
            log.info("Loaded {} identifier rows into stock_identifiers ({} parsed).",
                     inserted, ids.size());
        }

        // ── stock_prices ──────────────────────────────────────────────────────
        if (repository.hasPrices()) {
            log.info("stock_prices already populated – skipping.");
        } else {
            List<StockRepository.PriceRow> prices = parsePrices(pricesPath);
            int inserted = repository.insertPrices(prices);
            log.info("Loaded {} price rows into stock_prices ({} parsed).",
                     inserted, prices.size());
        }

        log.info("=== Load Complete ===");
    }

    // ── CSV parsers (package-visible for unit testing) ─────────────────────

    /**
     * Parses {@code stock_identifiers.csv} and returns a list of
     * {@link StockRepository.IdentifierRow} objects ready for batch insert.
     */
    List<StockRepository.IdentifierRow> parseIdentifiers(Path path) throws Exception {
        List<StockRepository.IdentifierRow> list = new ArrayList<>();
        int skipped = 0;

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSV_FORMAT.parse(reader)) {

            for (CSVRecord row : parser) {
                try {
                    long   idStock = normaliseId(row.get("id_stock"));
                    String name    = row.get("name").trim();
                    String symbol  = row.get("symbol").trim().toUpperCase();

                    if (name.isEmpty() || symbol.isEmpty()) {
                        log.warn("Skipping incomplete identifier row #{}", row.getRecordNumber());
                        skipped++;
                        continue;
                    }
                    list.add(new StockRepository.IdentifierRow(idStock, name, symbol));

                } catch (Exception e) {
                    log.warn("Skipping malformed identifier row #{}: {}",
                             row.getRecordNumber(), e.getMessage());
                    skipped++;
                }
            }
        }

        log.info("Parsed {} identifier rows, {} skipped from {}",
                 list.size(), skipped, path.getFileName());
        return list;
    }

    /**
     * Parses {@code stock_prices.csv} and returns a list of
     * {@link StockRepository.PriceRow} objects ready for batch insert.
     * All columns (high, low, close) are retained – unlike {@link CsvReaderService}
     * which only keeps the close price.
     */
    List<StockRepository.PriceRow> parsePrices(Path path) throws Exception {
        List<StockRepository.PriceRow> list = new ArrayList<>(1400);
        int skipped = 0;

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSV_FORMAT.parse(reader)) {

            for (CSVRecord row : parser) {
                try {
                    long       idStock = normaliseId(row.get("id_stock"));
                    BigDecimal high    = parseDecimal(row.get("high"),  row.getRecordNumber());
                    BigDecimal low     = parseDecimal(row.get("low"),   row.getRecordNumber());
                    BigDecimal close   = parseDecimal(row.get("close"), row.getRecordNumber());
                    LocalDate  date    = LocalDate.parse(row.get("date").trim(), DATE_FMT);

                    list.add(new StockRepository.PriceRow(idStock, high, low, close, date));

                } catch (Exception e) {
                    log.warn("Skipping malformed price row #{}: {}",
                             row.getRecordNumber(), e.getMessage());
                    skipped++;
                }
            }
        }

        log.info("Parsed {} price rows, {} skipped from {}",
                 list.size(), skipped, path.getFileName());
        return list;
    }

    // ── value helpers (package-visible for unit testing) ──────────────────────

    /**
     * Strips thousand-separator commas and trims whitespace, then parses as long.
     * E.g. {@code "40,359,100"} → {@code 40359100L}.
     */
    static long normaliseId(String raw) {
        return Long.parseLong(raw.replace(",", "").trim());
    }

    /**
     * Strips thousand-separator commas and trims whitespace, then parses as
     * {@link BigDecimal}.  E.g. {@code "6,894.87"} → {@code BigDecimal("6894.87")}.
     */
    private static BigDecimal parseDecimal(String raw, long rowNum) {
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Cannot parse decimal '%s' at row %d".formatted(raw, rowNum), e);
        }
    }
}

