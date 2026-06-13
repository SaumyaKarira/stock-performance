# Stock Performance Calculator — Part 1 + Part 2

A production-quality Java 17 / Maven application that processes historical stock prices, loads them into MySQL, computes monthly average closing prices via SQL, and emails the result.

---

## Table of Contents
1. [Project Structure](#project-structure)
2. [Architecture & Design Decisions](#architecture--design-decisions)
3. [Prerequisites](#prerequisites)
4. [Quick Start — Docker Compose](#quick-start--docker-compose)
5. [Task 1 — MySQL Database](#task-1--mysql-database)
6. [Task 2 — Monthly Average Prices](#task-2--monthly-average-prices)
7. [Task 3 — Email Job](#task-3--email-job)
8. [Task 4 — Docker Compose Networking](#task-4--docker-compose-networking)
9. [Task 5 — Database Initialization Strategy](#task-5--database-initialization-strategy)
10. [Connecting from DBeaver / MySQL Workbench](#connecting-from-dbeaver--mysql-workbench)
11. [Verifying the Generated CSV](#verifying-the-generated-csv)
12. [Part 1 — CSV Mode](#part-1--csv-mode)
13. [Running Tests](#running-tests)
14. [Environment Variables Reference](#environment-variables-reference)

---

## Project Structure

```
stock-performance/
├── files/
│   ├── stock_identifiers.csv        # 5 stock symbols (AAPL, GOOG, MSFT, NVDA, SPX)
│   └── stock_prices.csv             # ~1301 daily OHLC rows (Jan 2025 – Jan 2026)
├── sql/
│   └── schema.sql                   # MySQL schema (auto-loaded on first container start)
├── output/                          # Generated CSVs land here (Docker volume mount)
├── src/
│   └── main/java/com/project/
│       ├── Main.java                # Entry point (routes csv / db mode)
│       ├── config/
│       │   └── DatabaseConfig.java  # HikariCP DataSource factory
│       ├── model/
│       │   ├── MonthlyAverage.java  # Record: symbol, month, averagePrice
│       │   ├── PerformanceRecord.java
│       │   └── StockPrice.java
│       ├── repository/
│       │   └── StockRepository.java # JDBC repository (INSERT IGNORE + SQL aggregation)
│       ├── service/
│       │   ├── DatabaseLoaderService.java     # CSV → MySQL bulk loader
│       │   ├── MonthlyAverageCsvWriter.java   # Writes monthly_average_prices.csv
│       │   ├── MonthlyAverageService.java     # Orchestrator: DB → CSV → Email
│       │   ├── CsvReaderService.java
│       │   ├── CsvWriterService.java
│       │   └── EmailService.java
│       └── util/
│           └── FileUtils.java
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

---

## Architecture & Design Decisions

### Why Java-based CSV loading instead of `LOAD DATA INFILE`?

The `id_stock` column in both CSV files uses quoted comma-formatted numbers
(e.g. `"40,359,100"` = 40 359 100). MySQL's `LOAD DATA INFILE` cannot perform
this transformation without complex `@var` tricks. Additionally, `LOAD DATA INFILE`
requires MySQL's `secure_file_priv` to be configured, which complicates Docker setup.

Using the existing Apache Commons CSV parser in Java is:
- **Simpler** — reuses battle-tested code already in the project
- **More portable** — works on any OS without MySQL file permissions
- **More testable** — pure Java unit tests cover all edge cases

### Why HikariCP?

HikariCP is the fastest, most battle-tested connection pool on the JVM, used as the
default in Spring Boot. The pool is sized conservatively (max 5) since this is a
batch job, not a web server.

### Why SQL aggregation for monthly averages?

```sql
SELECT
    si.symbol,
    UPPER(DATE_FORMAT(sp.price_date, '%b')) AS month,
    ROUND(AVG(sp.close), 2)                AS average_price
FROM  stock_prices sp
INNER JOIN stock_identifiers si ON sp.id_stock = si.id_stock
GROUP BY si.symbol, YEAR(sp.price_date), MONTH(sp.price_date)
ORDER BY si.symbol ASC, YEAR(sp.price_date) ASC, MONTH(sp.price_date) ASC
```

Pushing aggregation to the database engine is:
- **Faster** than Java-side grouping loops for large datasets
- **Correct** — the DB handles precision and rounding consistently
- **Auditable** — the SQL is readable, testable, and independent of application code

### Why `INSERT IGNORE`?

Makes every `docker compose up` idempotent — re-running never duplicates rows.
The `UNIQUE KEY uq_stock_date (id_stock, price_date)` also enforces uniqueness
at the database level.

### Why Option A (MySQL schema + Java CSV loader)?

| | Option A (chosen) | Option B (MySQL-only) |
|---|---|---|
| Schema init | MySQL init script | MySQL init script |
| Data loading | Java application | `LOAD DATA INFILE` |
| Handles quoted IDs | ✅ Java parsing | ❌ Needs workarounds |
| Portable | ✅ No file permissions | ❌ Needs `secure_file_priv` |
| Testable | ✅ JUnit + TempDir | ❌ Hard to unit test |

---

## Prerequisites

- Docker Desktop (includes `docker compose`)
- Java 17+ and Maven (only needed for local development / tests)

---

## Quick Start — Docker Compose

```bash
# 1. Clone / open the project
cd stock-performance

# 2. Build and run all services
docker compose up --build

# 3. Check email in MailHog web UI
open http://localhost:8025

# 4. Find the generated CSV
cat output/monthly_average_prices.csv
```

The first run will:
1. Start MySQL 8, run `schema.sql`, create `stockdb` with two tables
2. Start MailHog (fake SMTP)
3. Start the app, wait for MySQL to be healthy, then:
   - Load `stock_identifiers.csv` → `stock_identifiers` table
   - Load `stock_prices.csv` → `stock_prices` table (~1301 rows)
   - Query monthly averages via SQL
   - Write `output/monthly_average_prices.csv`
   - Email the CSV to MailHog

Subsequent `docker compose up` runs skip the CSV load (tables already populated).

To reset the database:
```bash
docker compose down -v   # removes the mysql-data volume
docker compose up --build
```

---

## Task 1 — MySQL Database

The `mysql` service uses the official `mysql:8.0` image:

| Property | Value |
|---|---|
| Host (from host machine) | `localhost:3306` |
| Host (from other containers) | `mysql:3306` |
| Database | `stockdb` |
| User | `stockuser` |
| Password | `stockpass` |
| Root password | `rootpassword` |

Schema (see `sql/schema.sql`):

```sql
stock_identifiers (id_stock BIGINT PK, name VARCHAR, symbol VARCHAR UNIQUE)
stock_prices      (id INT PK AUTO_INCREMENT, id_stock FK, high, low, close DECIMAL(14,4), price_date DATE, UNIQUE(id_stock, price_date))
```

---

## Task 2 — Monthly Average Prices

Output file: `output/monthly_average_prices.csv`

Format:
```
symbol,month,average_price
AAPL,JAN,222.77
AAPL,FEB,229.61
...
```

The dataset spans January 2025 to January 2026 (approximately 13 months). The query
groups by `(symbol, year, month)`, so January appears twice per symbol — once for
Jan 2025 and once for the partial Jan 2026 data. This is the accurate representation
of the dataset.

---

## Task 3 — Email Job

The existing `EmailService` (Jakarta Mail / Angus Mail) is reused unchanged.
`MonthlyAverageService` calls it after writing the CSV:

```
EmailService.EmailConfig.fromEnv()  → reads SMTP_HOST, SMTP_PORT, etc.
EmailService.send(List.of(csvFile)) → attaches monthly_average_prices.csv
```

**MailHog** (local testing):
- SMTP: `localhost:1025`
- Web UI: http://localhost:8025
- No authentication, no TLS

**Production SMTP**:
```bash
EMAIL_TO=you@example.com SMTP_HOST=smtp.gmail.com SMTP_PORT=587 \
SMTP_AUTH=true SMTP_TLS=true SMTP_USER=you@gmail.com SMTP_PASS=apppassword \
java -jar stock-performance.jar db
```

---

## Task 4 — Docker Compose Networking

```
┌─────────────────────────────────────────────────────────────┐
│                   Docker default bridge network              │
│                                                             │
│  ┌──────────┐   JDBC :3306    ┌─────────┐                   │
│  │   app    │ ──────────────▶ │  mysql  │                   │
│  │          │                 └─────────┘                   │
│  │          │   SMTP :1025    ┌──────────┐                  │
│  │          │ ──────────────▶ │ mailhog  │                  │
│  └──────────┘                 └──────────┘                  │
└─────────────────────────────────────────────────────────────┘
         │                         │            │
   host:output/              host:3306    host:8025
```

- Docker Compose creates a default bridge network.
- Service names (`mysql`, `mailhog`) are DNS-resolvable inside the network.
- `app` uses `DB_HOST=mysql` and `SMTP_HOST=mailhog` — these resolve to the correct
  container IP addresses automatically.
- The `depends_on: mysql: condition: service_healthy` ensures MySQL's healthcheck
  passes before the app starts (healthcheck uses `mysqladmin ping`).
- Port 3306 is exposed to the host for GUI clients (DBeaver, MySQL Workbench).
- Port 8025 is exposed to the host for the MailHog Web UI.

---

## Task 5 — Database Initialization Strategy

**Chosen: Option A — MySQL auto-schema + Java CSV loader**

1. `sql/schema.sql` is mounted to `/docker-entrypoint-initdb.d/01-schema.sql`
2. MySQL executes it **once** when the data volume is first created
3. The Java application loads the CSV data on startup using JDBC batch inserts

This is more production-quality than `LOAD DATA INFILE` because:
- No MySQL file permission configuration required
- Reuses the project's existing Apache Commons CSV parsing
- Fully idempotent: `INSERT IGNORE` + existence check prevents duplicate loads
- Unit-testable with `@TempDir` fixtures

---

## Connecting from DBeaver / MySQL Workbench

1. Start the stack: `docker compose up`
2. Open your SQL client and create a new **MySQL** connection:

| Field | Value |
|---|---|
| Host | `127.0.0.1` |
| Port | `3306` |
| Database | `stockdb` |
| Username | `stockuser` |
| Password | `stockpass` |

3. Verify the data:
```sql
SELECT COUNT(*) FROM stock_identifiers;   -- expect 5
SELECT COUNT(*) FROM stock_prices;        -- expect ~1301
SELECT * FROM stock_identifiers;
SELECT * FROM stock_prices LIMIT 10;
```

4. Run the monthly average query manually:
```sql
SELECT
    si.symbol,
    UPPER(DATE_FORMAT(sp.price_date, '%b')) AS month,
    ROUND(AVG(sp.close), 2)                AS average_price
FROM  stock_prices sp
INNER JOIN stock_identifiers si ON sp.id_stock = si.id_stock
GROUP BY si.symbol, YEAR(sp.price_date), MONTH(sp.price_date)
ORDER BY si.symbol ASC, YEAR(sp.price_date) ASC, MONTH(sp.price_date) ASC;
```

---

## Verifying the Generated CSV

```bash
# After docker compose up
cat output/monthly_average_prices.csv

# Count rows: 5 symbols × up to 13 months + 1 header
wc -l output/monthly_average_prices.csv

# Spot-check AAPL
grep "^AAPL" output/monthly_average_prices.csv

# Open in Excel / Numbers
open output/monthly_average_prices.csv
```

Expected first few lines:
```
symbol,month,average_price
AAPL,JAN,222.77
AAPL,FEB,229.61
AAPL,MAR,220.04
...
```

---

## Part 1 — CSV Mode

The original CSV-only mode is preserved. To run it:

```bash
# Via Docker Compose (override the command)
docker compose run app files/stock_prices.csv 7

# Locally
java -jar target/stock-performance.jar files/stock_prices.csv 7

# Without interval (produces 7day.csv, 14day.csv, 30day.csv)
java -jar target/stock-performance.jar files/stock_prices.csv
```

---

## Running Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=StockRepositoryTest

# With full output
mvn test -pl . --no-transfer-progress
```

Tests use Mockito 5 with the ByteBuddy subclass mock maker
(`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`)
for compatibility with JDK 21+.

**Test coverage:**
| Class | Tests |
|---|---|
| `StockRepository` | 10 |
| `DatabaseLoaderService` | 8 |
| `MonthlyAverageService` | 7 |
| `MonthlyAverageCsvWriter` | 5 |
| `CsvReaderService` | 9 |
| `CsvWriterService` | 7 |
| `PerformanceCalculator` | 12 |
| `Model` | 11 |
| **Total** | **69** |

---

## Environment Variables Reference

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | MySQL hostname |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `stockdb` | Database name |
| `DB_USER` | `stockuser` | Database username |
| `DB_PASSWORD` | `stockpass` | Database password |
| `SMTP_HOST` | `localhost` | SMTP hostname (use `mailhog` in Docker) |
| `SMTP_PORT` | `1025` | SMTP port |
| `SMTP_AUTH` | `false` | Enable SMTP authentication |
| `SMTP_TLS` | `false` | Enable STARTTLS |
| `SMTP_USER` | _(empty)_ | SMTP username |
| `SMTP_PASS` | _(empty)_ | SMTP password |
| `EMAIL_FROM` | `reports@stock-performance.local` | Sender address |
| `EMAIL_FROM_NAME` | `Stock Performance Bot` | Sender display name |
| `EMAIL_TO` | _(empty)_ | Recipient address (email skipped if not set) |
