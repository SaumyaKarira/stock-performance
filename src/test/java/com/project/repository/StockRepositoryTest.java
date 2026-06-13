package com.project.repository;

import com.project.model.MonthlyAverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StockRepository}.
 *
 * <p>All JDBC objects (DataSource, Connection, PreparedStatement, ResultSet) are
 * mocked with Mockito so no real database is required. This keeps tests fast,
 * deterministic, and runnable in any CI environment.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockRepository")
class StockRepositoryTest {

    @Mock DataSource        dataSource;
    @Mock Connection        connection;
    @Mock PreparedStatement preparedStatement;
    @Mock ResultSet         resultSet;

    private StockRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new StockRepository(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    // ── constructor ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("constructor rejects null DataSource")
    void constructor_rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StockRepository(null))
                .withMessageContaining("dataSource");
    }

    // ── hasIdentifiers ────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasIdentifiers returns true when count > 0")
    void hasIdentifiers_trueWhenRowsExist() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(5L);

        assertThat(repo.hasIdentifiers()).isTrue();
    }

    @Test
    @DisplayName("hasIdentifiers returns false when count == 0")
    void hasIdentifiers_falseWhenEmpty() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(0L);

        assertThat(repo.hasIdentifiers()).isFalse();
    }

    // ── insertIdentifiers ─────────────────────────────────────────────────────

    @Test
    @DisplayName("insertIdentifiers returns 0 for an empty list without touching the DB")
    void insertIdentifiers_emptyList_returnsZero() throws Exception {
        int result = repo.insertIdentifiers(List.of());
        assertThat(result).isZero();
        verifyNoInteractions(dataSource); // no DB call made
    }

    @Test
    @DisplayName("insertIdentifiers commits and returns batch total")
    void insertIdentifiers_insertsAndCommits() throws Exception {
        List<StockRepository.IdentifierRow> rows = List.of(
                new StockRepository.IdentifierRow(13L, "Apple Inc.", "AAPL"),
                new StockRepository.IdentifierRow(5094L, "Microsoft Corporation", "MSFT")
        );

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeBatch()).thenReturn(new int[]{1, 1});

        int result = repo.insertIdentifiers(rows);

        assertThat(result).isEqualTo(2);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    // ── insertPrices ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("insertPrices returns 0 for an empty list without touching the DB")
    void insertPrices_emptyList_returnsZero() throws Exception {
        int result = repo.insertPrices(List.of());
        assertThat(result).isZero();
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("insertPrices commits with SUCCESS_NO_INFO counted as 1 row")
    void insertPrices_successNoInfo_countedAsOne() throws Exception {
        List<StockRepository.PriceRow> rows = List.of(
                new StockRepository.PriceRow(13L,
                        new BigDecimal("230.00"),
                        new BigDecimal("220.00"),
                        new BigDecimal("225.00"),
                        LocalDate.of(2025, 1, 20))
        );

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        // MySQL with rewriteBatchedStatements returns SUCCESS_NO_INFO (-2)
        when(preparedStatement.executeBatch())
                .thenReturn(new int[]{Statement.SUCCESS_NO_INFO});

        int result = repo.insertPrices(rows);

        assertThat(result).isEqualTo(1);
        verify(connection).commit();
    }

    // ── getMonthlyAverages ────────────────────────────────────────────────────

    @Test
    @DisplayName("getMonthlyAverages returns empty list when ResultSet is empty")
    void getMonthlyAverages_emptyResultSet() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false); // no rows

        List<MonthlyAverage> result = repo.getMonthlyAverages();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getMonthlyAverages maps ResultSet rows to MonthlyAverage objects")
    void getMonthlyAverages_mapsRows() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        // Simulate two result rows, then EOF
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("symbol")).thenReturn("AAPL", "GOOG");
        when(resultSet.getString("month")).thenReturn("JAN", "JAN");
        when(resultSet.getBigDecimal("average_price"))
                .thenReturn(new BigDecimal("222.77"), new BigDecimal("185.34"));

        List<MonthlyAverage> result = repo.getMonthlyAverages();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).month()).isEqualTo("JAN");
        assertThat(result.get(0).averagePrice()).isEqualByComparingTo("222.77");
        assertThat(result.get(1).symbol()).isEqualTo("GOOG");
    }

    // ── SQL_MONTHLY_AVG constant ──────────────────────────────────────────────

    @Test
    @DisplayName("SQL_MONTHLY_AVG contains required SQL keywords")
    void sqlMonthlyAvg_containsExpectedKeywords() {
        String sql = StockRepository.SQL_MONTHLY_AVG.toLowerCase();
        assertThat(sql).contains("avg(");
        assertThat(sql).contains("group by");
        assertThat(sql).contains("order by");
        assertThat(sql).contains("inner join");
        assertThat(sql).contains("upper(date_format(");
    }
}



