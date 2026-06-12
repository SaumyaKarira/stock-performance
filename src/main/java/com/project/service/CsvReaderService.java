package com.project.service;

import com.project.model.StockPrice;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Reads and parses the two input CSV files, then produces a ready-to-use
 * list of {@link StockPrice} objects sorted by symbol then date ascending.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>Apache Commons CSV is used for correct RFC-4180 parsing (handles
 *       quoted fields that contain commas, e.g. {@code "7,458"}).</li>
 *   <li>The two files are parsed separately: identifiers once (in memory)
 *       and prices streamed record-by-record to keep heap usage low.</li>
 *   <li>Any row that cannot be parsed is logged and skipped rather than
 *       aborting the whole run — robust handling for real-world dirty data.</li>
 * </ul>
 *
 * <h2>CSV schemas</h2>
 * <pre>
 *  stock_identifiers.csv  →  id_stock, name, symbol
 *  stock_prices.csv       →  id_stock, high, low, close, date
 * </pre>
 */
public class CsvReaderService {

    private static final Logger log = LoggerFactory.getLogger(CsvReaderService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd

    // ── column header constants ──────────────────────────────────────────────
    private static final String COL_ID      = "id_stock";
    private static final String COL_SYMBOL  = "symbol";
    private static final String COL_CLOSE   = "close";
    private static final String COL_DATE    = "date";

    /** Shared CSV format: first row is header, surrounding whitespace trimmed. */
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Reads both CSV files and returns all parsed price records, sorted by
     * symbol (ascending) then date (ascending).
     *
     * @param identifiersPath path to {@code stock_identifiers.csv}
     * @param pricesPath      path to {@code stock_prices.csv}
     * @return unmodifiable, sorted list of {@link StockPrice}
     * @throws IOException              if a file cannot be opened
     * @throws IllegalArgumentException if neither file has parseable data
     */
    public List<StockPrice> read(Path identifiersPath, Path pricesPath) throws IOException {
        Map<String, String> idToSymbol = readIdentifiers(identifiersPath);
        log.info("Loaded {} stock identifiers from {}", idToSymbol.size(), identifiersPath.getFileName());

        List<StockPrice> prices = readPrices(pricesPath, idToSymbol);
        log.info("Loaded {} price records from {}", prices.size(), pricesPath.getFileName());

        if (prices.isEmpty()) {
            throw new IllegalArgumentException(
                    "No valid price records found in " + pricesPath + ". Check the file format.");
        }

        prices.sort(Comparator
                .comparing(StockPrice::getSymbol)
                .thenComparing(StockPrice::getDate));

        return Collections.unmodifiableList(prices);
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    /**
     * Parses the identifiers file and returns a map of {@code id_stock → symbol}.
     *
     * <p>The {@code id_stock} values in the prices file are quoted CSV numbers that
     * may contain commas (e.g. {@code "40,359,100"}). Commons CSV strips the outer
     * quotes, so the raw string we get is {@code 40,359,100}.  We normalise both
     * the key from this file and the value from the prices file by removing any
     * remaining commas so the lookup always succeeds.</p>
     */
    Map<String, String> readIdentifiers(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSV_FORMAT.parse(reader)) {

            for (CSVRecord row : parser) {
                try {
                    String id     = normaliseId(row.get(COL_ID));
                    String symbol = row.get(COL_SYMBOL).trim().toUpperCase();
                    if (id.isEmpty() || symbol.isEmpty()) {
                        log.warn("Skipping incomplete identifier row #{}: {}", row.getRecordNumber(), row);
                        continue;
                    }
                    map.put(id, symbol);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    log.warn("Skipping malformed identifier row #{}: {} — {}",
                            row.getRecordNumber(), row, e.getMessage());
                }
            }
        }
        return map;
    }

    /**
     * Parses the prices file. Rows whose {@code id_stock} cannot be resolved to a
     * symbol, or that contain unparseable numbers/dates, are logged and skipped.
     */
    private List<StockPrice> readPrices(Path path, Map<String, String> idToSymbol) throws IOException {
        List<StockPrice> list = new ArrayList<>(1400); // ~1300 lines in the sample
        int skipped = 0;

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSV_FORMAT.parse(reader)) {

            for (CSVRecord row : parser) {
                try {
                    String id     = normaliseId(row.get(COL_ID));
                    String symbol = idToSymbol.get(id);

                    if (symbol == null) {
                        log.debug("Unknown id_stock '{}' at row #{}, skipping.", id, row.getRecordNumber());
                        skipped++;
                        continue;
                    }

                    BigDecimal close = parsePrice(row.get(COL_CLOSE), row.getRecordNumber());
                    LocalDate  date  = parseDate(row.get(COL_DATE),   row.getRecordNumber());

                    list.add(new StockPrice(symbol, date, close));

                } catch (IllegalArgumentException | IllegalStateException e) {
                    log.warn("Skipping malformed price row #{}: {} — {}",
                            row.getRecordNumber(), row, e.getMessage());
                    skipped++;
                }
            }
        }

        if (skipped > 0) {
            log.warn("Skipped {} malformed or unresolvable price rows.", skipped);
        }
        return list;
    }

    // ── value parsers ────────────────────────────────────────────────────────

    /**
     * Strips commas from an id_stock string so that {@code "40,359,100"} and
     * {@code 40359100} both normalise to the same key.
     */
    static String normaliseId(String raw) {
        return raw.replace(",", "").trim();
    }

    private BigDecimal parsePrice(String raw, long rowNum) {
        try {
            // Strip thousand-separator commas before parsing (e.g. "5,996.66" → "5996.66").
            // The surrounding quotes are already removed by Commons CSV; we may still see
            // embedded commas if the value was not quoted or if a locale uses commas.
            String normalised = raw.trim().replace(",", "");
            return new BigDecimal(normalised);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Cannot parse close price '%s' at row %d".formatted(raw, rowNum), e);
        }
    }

    private LocalDate parseDate(String raw, long rowNum) {
        try {
            return LocalDate.parse(raw.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Cannot parse date '%s' at row %d".formatted(raw, rowNum), e);
        }
    }
}


