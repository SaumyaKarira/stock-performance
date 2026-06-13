package com.project.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Factory for the application's JDBC {@link DataSource} backed by HikariCP.
 *
 * <h2>Why HikariCP?</h2>
 * HikariCP is the fastest, most battle-tested connection pool on the JVM.
 * It is already the default in Spring Boot and is a natural fit for any
 * Java application that talks to a relational database.
 *
 * <h2>Configuration</h2>
 * Every parameter is read from environment variables with sensible defaults,
 * so the same fat-jar works:
 * <ul>
 *   <li>Locally → pointing at {@code localhost:3306}</li>
 *   <li>Inside Docker Compose → pointing at the {@code mysql} service</li>
 * </ul>
 *
 * <h2>Notable JDBC URL options</h2>
 * <ul>
 *   <li>{@code useSSL=false} – no SSL needed for a local/compose deployment</li>
 *   <li>{@code allowPublicKeyRetrieval=true} – required by MySQL 8 caching_sha2_password</li>
 *   <li>{@code rewriteBatchedStatements=true} – converts individual batch INSERTs
 *       into a single multi-row statement, dramatically speeding up the CSV bulk-load</li>
 *   <li>{@code serverTimezone=UTC} – avoids ambiguous DST conversions</li>
 * </ul>
 */
public final class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private DatabaseConfig() { /* utility class – no instances */ }

    /**
     * Creates and returns a configured {@link HikariDataSource}.
     *
     * <p>The pool is sized for a batch-job workload (max 5 connections).
     * Callers are responsible for closing the returned DataSource when done.</p>
     */
    public static DataSource createDataSource() {
        String host     = env("DB_HOST",     "localhost");
        String port     = env("DB_PORT",     "3306");
        String dbName   = env("DB_NAME",     "stockdb");
        String user     = env("DB_USER",     "stockuser");
        String password = env("DB_PASSWORD", "stockpass");

        String url = "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true&characterEncoding=UTF-8"
                .formatted(host, port, dbName);

        log.info("Configuring HikariCP → jdbc:mysql://{}:{}/{}", host, port, dbName);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Pool sizing: batch job, not a web server → small pool is fine.
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(30_000L);   // 30 s
        cfg.setIdleTimeout(600_000L);        // 10 min
        cfg.setMaxLifetime(1_800_000L);      // 30 min
        cfg.setPoolName("StockPool");

        // Validate connections before use; detects stale connections after MySQL restart.
        cfg.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(cfg);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String env(String key, String defaultVal) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v.trim() : defaultVal;
    }
}

