package com.project.repository;

import com.project.model.MonthlyAverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC repository for all stock-data database interactions.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Pure JDBC</b> – no ORM; minimal overhead, direct SQL control.</li>
 *   <li><b>INSERT IGNORE</b> – idempotent bulk inserts; re-running the app never
 *       creates duplicate rows (the UNIQUE key on stock_prices enforces this at
 *       the DB level too).</li>
 *   <li><b>Batch inserts</b> – rows are sent to MySQL in batches of
 *       {@value #BATCH_SIZE}. Combined with the {@code rewriteBatchedStatements}
 *       JDBC URL flag, this turns ~1300 individual INSERTs into a handful of
 *       multi-row statements, dramatically reducing round-trips.</li>
 *   <li><b>SQL aggregation for monthly averages</b> – pushing GROUP BY + AVG to
 *       the database engine is faster and more correct than doing it in Java
 *       loops. It also makes the query visible and auditable.</li>
 *   <li><b>Explicit transactions</b> – autoCommit is disabled during bulk loads;
 *       a rollback happens if any batch fails.</li>
 * </ul>
 */
public class StockRepository {

    private static final Logger log = LoggerFactory.getLogger(StockRepository.class);

    /** Number of rows per JDBC batch flush. */
    static final int BATCH_SIZE = 500;

    /**
     * Monthly-average SQL.
     *
     * <p>Groups by (symbol, year, month, date_format) so that partial months at the start
         * and end of the dataset produce their own rows. {@code UPPER(DATE_FORMAT(…,'%b'))}
         * converts MySQL's abbreviated month name ('Jan') to the required 'JAN' format.
         * The exact formatted expression is included in the GROUP BY to satisfy
         * MySQL's {@code ONLY_FULL_GROUP_BY} sql_mode.</p>
     *
     * <p>Ordering: symbol A→Z, then chronological year/month.</p>
     */
    static final String SQL_MONTHLY_AVG = """
            SELECT
                si.symbol,
                UPPER(DATE_FORMAT(sp.price_date, '%b')) AS month,
                ROUND(AVG(sp.close), 2)                 AS average_price
            FROM  stock_prices sp
            INNER JOIN stock_identifiers si ON sp.id_stock = si.id_stock
            GROUP BY
                si.symbol,
                YEAR(sp.price_date),
                MONTH(sp.price_date),
                UPPER(DATE_FORMAT(sp.price_date, '%b'))
            ORDER BY
                si.symbol           ASC,
                YEAR(sp.price_date) ASC,
                MONTH(sp.price_date) ASC
            """;

    // ── nested row holders ────────────────────────────────────────────────────

    /** Raw data for one row of the {@code stock_identifiers} table. */
    public record IdentifierRow(long idStock, String name, String symbol) {}

    /** Raw data for one row of the {@code stock_prices} table. */
    public record PriceRow(long idStock,
                           BigDecimal high,
                           BigDecimal low,
                           BigDecimal close,
                           LocalDate date) {}

    // ── fields ────────────────────────────────────────────────────────────────

    private final DataSource dataSource;

    public StockRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    // ── existence checks ──────────────────────────────────────────────────────

    /** @return {@code true} if {@code stock_identifiers} contains at least one row */
    public boolean hasIdentifiers() throws SQLException {
        return countRows("stock_identifiers") > 0;
    }

    /** @return {@code true} if {@code stock_prices} contains at least one row */
    public boolean hasPrices() throws SQLException {
        return countRows("stock_prices") > 0;
    }

    private long countRows(String table) throws SQLException {
        // table name is a constant – no SQL-injection risk.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ── bulk inserts ──────────────────────────────────────────────────────────

    /**
     * Inserts stock identifiers using {@code INSERT IGNORE} (idempotent).
     *
     * @param rows list of identifier rows to insert
     * @return number of rows actually inserted (duplicates are skipped)
     * @throws SQLException on any database error; the transaction is rolled back
     */
    public int insertIdentifiers(List<IdentifierRow> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) return 0;

        final String sql =
                "INSERT IGNORE INTO stock_identifiers (id_stock, name, symbol) VALUES (?,?,?)";
        int total = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int batchCount = 0;
                for (IdentifierRow r : rows) {
                    ps.setLong(1,   r.idStock());
                    ps.setString(2, r.name());
                    ps.setString(3, r.symbol());
                    ps.addBatch();
                    if (++batchCount % BATCH_SIZE == 0) {
                        total += sumBatch(ps.executeBatch());
                        log.debug("Flushed identifier batch; running total={}", total);
                    }
                }
                total += sumBatch(ps.executeBatch()); // flush remainder
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return total;
    }

    /**
     * Inserts daily price rows using {@code INSERT IGNORE} (idempotent).
     *
     * @param rows list of price rows to insert
     * @return number of rows actually inserted (duplicates are skipped)
     * @throws SQLException on any database error; the transaction is rolled back
     */
    public int insertPrices(List<PriceRow> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) return 0;

        final String sql =
                "INSERT IGNORE INTO stock_prices (id_stock, high, low, close, price_date)" +
                " VALUES (?,?,?,?,?)";
        int total = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int batchCount = 0;
                for (PriceRow r : rows) {
                    ps.setLong(1,       r.idStock());
                    ps.setBigDecimal(2, r.high());
                    ps.setBigDecimal(3, r.low());
                    ps.setBigDecimal(4, r.close());
                    ps.setDate(5,       Date.valueOf(r.date()));
                    ps.addBatch();
                    if (++batchCount % BATCH_SIZE == 0) {
                        total += sumBatch(ps.executeBatch());
                        log.debug("Flushed price batch; running total={}", total);
                    }
                }
                total += sumBatch(ps.executeBatch()); // flush remainder
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return total;
    }

    // ── queries ───────────────────────────────────────────────────────────────

    /**
     * Executes the monthly-average SQL query and returns the results.
     *
     * <p>All aggregation is performed in the database (GROUP BY + AVG + ROUND).
     * No Java-side summation or grouping is needed.</p>
     *
     * @return list of monthly averages ordered by symbol then chronological month
     * @throws SQLException on any database error
     */
    public List<MonthlyAverage> getMonthlyAverages() throws SQLException {
        List<MonthlyAverage> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_MONTHLY_AVG);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(new MonthlyAverage(
                        rs.getString("symbol"),
                        rs.getString("month"),
                        rs.getBigDecimal("average_price")));
            }
        }
        log.info("Monthly-average query returned {} rows.", results.size());
        return results;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Sums the update counts returned by {@link PreparedStatement#executeBatch()}.
     * Handles {@link Statement#SUCCESS_NO_INFO} (-2) which MySQL may return
     * when {@code rewriteBatchedStatements=true} is active.
     */
    private static int sumBatch(int[] counts) {
        int n = 0;
        for (int c : counts) {
            if (c > 0) n += c;
            else if (c == Statement.SUCCESS_NO_INFO) n++;
            // Statement.EXECUTE_FAILED (-3) is intentionally ignored –
            // INSERT IGNORE means failures are expected for duplicates.
        }
        return n;
    }
}

