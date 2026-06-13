-- ─────────────────────────────────────────────────────────────────────────────
--  schema.sql
--
--  MySQL 8 schema for the stock-performance database.
--  Mounted to /docker-entrypoint-initdb.d/ so MySQL executes this file
--  automatically the first time the container starts with an empty data volume.
--
--  Design decisions:
--  • utf8mb4 / utf8mb4_unicode_ci – full Unicode support, including emoji in names
--  • DECIMAL(14,4) for prices – exact arithmetic; avoids floating-point drift
--  • UNIQUE KEY on (id_stock, price_date) – prevents duplicate daily records
--  • Foreign key from stock_prices → stock_identifiers – referential integrity
--  • ON DELETE CASCADE – removing an identifier removes its prices automatically
--  • InnoDB engine – supports transactions & foreign keys
-- ─────────────────────────────────────────────────────────────────────────────

CREATE DATABASE IF NOT EXISTS stockdb
    CHARACTER SET  utf8mb4
    COLLATE        utf8mb4_unicode_ci;

USE stockdb;

-- ── Table: stock_identifiers ─────────────────────────────────────────────────
--  Maps a numeric id_stock to a human-readable name and ticker symbol.
--  id_stock values in the CSV use quoted comma formatting (e.g. "40,359,100");
--  the Java loader strips the commas before inserting.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_identifiers (
    id_stock  BIGINT       NOT NULL COMMENT 'Numeric stock ID (commas stripped)',
    name      VARCHAR(255) NOT NULL COMMENT 'Full company / index name',
    symbol    VARCHAR(10)  NOT NULL COMMENT 'Ticker symbol e.g. AAPL',
    PRIMARY KEY (id_stock),
    UNIQUE  KEY uq_symbol (symbol)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Master list of stock identifiers';

-- ── Table: stock_prices ──────────────────────────────────────────────────────
--  Daily OHLC price records.  The close column drives all analytics.
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_prices (
    id         INT            NOT NULL AUTO_INCREMENT      COMMENT 'Surrogate PK',
    id_stock   BIGINT         NOT NULL                     COMMENT 'FK → stock_identifiers',
    high       DECIMAL(14, 4) NOT NULL                     COMMENT 'Intra-day high',
    low        DECIMAL(14, 4) NOT NULL                     COMMENT 'Intra-day low',
    close      DECIMAL(14, 4) NOT NULL                     COMMENT 'Closing price',
    price_date DATE           NOT NULL                     COMMENT 'Trading date (YYYY-MM-DD)',
    PRIMARY KEY (id),
    UNIQUE  KEY uq_stock_date (id_stock, price_date),
    CONSTRAINT fk_prices_stock
        FOREIGN KEY (id_stock)
        REFERENCES stock_identifiers (id_stock)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Daily high / low / close prices per stock';

