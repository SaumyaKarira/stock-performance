# Stock Performance Calculator

A **Java 17 / Maven** command-line application that calculates N-trading-day
price performance for a configurable set of stock symbols, writes output CSV
reports, and optionally e-mails the results via SMTP (with MailHog for local
testing).

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Assumptions](#assumptions)
3. [Architecture Explanation](#architecture-explanation)
4. [Folder Structure](#folder-structure)
5. [Build Instructions](#build-instructions)
6. [Run Instructions](#run-instructions)
7. [Docker Instructions](#docker-instructions)
8. [Sample Commands](#sample-commands)
9. [Email Configuration](#email-configuration)
10. [Design Decisions](#design-decisions)

---

## Project Overview

Given two CSV files:

| File | Description |
|---|---|
| `stock_identifiers.csv` | Maps numeric `id_stock` → ticker symbol (AAPL, GOOG, …) |
| `stock_prices.csv` | One year of daily OHLC prices, keyed by `id_stock` |

The application:

1. Reads and joins the two files.
2. Groups price records by symbol.
3. Calculates **% performance** over the specified number of **trading days**
   (weekends and market holidays are automatically excluded because they simply
   do not appear in the price records).
4. Writes one output CSV per interval (`7day.csv`, `14day.csv`, `30day.csv`).
5. Sends the CSV files as email attachments (MailHog for local testing).

**Formula:**

```
performance = ((closePrice[i] - closePrice[i - N]) / closePrice[i - N]) × 100
```

---

## Assumptions

| # | Assumption |
|---|---|
| 1 | The `stock_prices.csv` contains **only trading days**. Weekends and market holidays are absent from the file, so no calendar logic is needed. |
| 2 | The `id_stock` column may be a quoted number containing commas (e.g. `"40,359,100"`). The application normalises these by removing commas before using them as lookup keys. |
| 3 | The `close` price is used as the representative daily price (not `open`, `high`, or `low`). |
| 4 | The three required output intervals are **7, 14, and 30** trading days. These are always computed regardless of the CLI interval. |
| 5 | The CLI interval is an _additional_ interval computed on top of the required three (unless it is already 7, 14, or 30). |
| 6 | The identifiers file must be located in the **same directory** as the prices file and must be named `stock_identifiers.csv`. |
| 7 | Output CSVs are written to an `output/` directory relative to the current working directory. |
| 8 | Email sending is **best-effort**: if SMTP fails, the application logs a warning but exits with code 0. |
| 9 | `BigDecimal` is used for all price arithmetic to avoid floating-point rounding errors. |
| 10 | Performance values are rounded to **4 decimal places** (basis-point precision). |

---

## Architecture Explanation

```
com.nasdaq
├── Main                  Entry point; argument validation + orchestration
├── model
│   ├── StockPrice         Value object: symbol + date + close price
│   └── PerformanceRecord  Value object: date + symbol + computed % performance
├── service
│   ├── CsvReaderService   Reads & joins the two input CSVs → List<StockPrice>
│   ├── PerformanceCalculator  Core business logic → List<PerformanceRecord>
│   ├── CsvWriterService   Writes PerformanceRecords to output CSV
│   └── EmailService       Sends output CSVs as SMTP attachments
└── util
    └── FileUtils          Path resolution + directory creation helpers
```

### Data flow

```
stock_identifiers.csv ──┐
                        ├─→ CsvReaderService ─→ List<StockPrice>
stock_prices.csv ───────┘                              │
                                                       ▼
                                         PerformanceCalculator
                                                       │
                                         List<PerformanceRecord>
                                                       │
                                           CsvWriterService
                                                       │
                                         7day.csv / 14day.csv / 30day.csv
                                                       │
                                           EmailService (best-effort)
```

---

## Folder Structure

```
stock-performance/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── README.md
├── files/
│   ├── stock_identifiers.csv
│   └── stock_prices.csv
├── output/                          ← generated at runtime
│   ├── 7day.csv
│   ├── 14day.csv
│   └── 30day.csv
└── src/
    ├── main/
    │   ├── java/com/nasdaq/
    │   │   ├── Main.java
    │   │   ├── model/
    │   │   │   ├── StockPrice.java
    │   │   │   └── PerformanceRecord.java
    │   │   ├── service/
    │   │   │   ├── CsvReaderService.java
    │   │   │   ├── PerformanceCalculator.java
    │   │   │   ├── CsvWriterService.java
    │   │   │   └── EmailService.java
    │   │   └── util/
    │   │       └── FileUtils.java
    │   └── resources/
    │       └── logback.xml
    └── test/
        ├── java/com/nasdaq/
        │   ├── model/
        │   │   └── ModelTest.java
        │   └── service/
        │       ├── CsvReaderServiceTest.java
        │       ├── PerformanceCalculatorTest.java
        │       └── CsvWriterServiceTest.java
        └── resources/
            ├── test_identifiers.csv
            └── test_prices.csv
```

---

## Build Instructions

### Prerequisites

- Java 17+
- Maven 3.8+

```bash
# Clone / open the project, then:
cd stock-performance

# Compile and run unit tests
mvn clean verify

# Build the fat jar (skipping tests)
mvn clean package -DskipTests

# The jar is at:
target/stock-performance.jar
```

---

## Run Instructions

```bash
java -jar target/stock-performance.jar <path/to/stock_prices.csv> <interval>
```

| Argument | Description |
|---|---|
| `stock_prices.csv` | Path to the prices file (relative or absolute). The identifiers file must be in the same directory. |
| `interval` | Positive integer — number of trading days to look back. |

**Examples:**

```bash
# 7-day performance (also produces 14day.csv and 30day.csv)
java -jar target/stock-performance.jar files/stock_prices.csv 7

# 30-day performance
java -jar target/stock-performance.jar files/stock_prices.csv 30

# Custom 60-day performance (produces 7day.csv, 14day.csv, 30day.csv, AND 60day.csv)
java -jar target/stock-performance.jar files/stock_prices.csv 60
```

Output files are created in `./output/`.

---

## Docker Instructions

### Build the Docker image

```bash
docker build -t stock-performance:latest .
```

### Run with Docker (standalone)

```bash
# Default: 7-day performance
docker run --rm -v "$(pwd)/output:/app/output" stock-performance:latest

# Custom interval
docker run --rm -v "$(pwd)/output:/app/output" \
  stock-performance:latest files/stock_prices.csv 30
```

### Run with Docker Compose (includes MailHog)

```bash
# Start MailHog + run app with default interval=7
docker compose up --build

# Run app with a different interval (MailHog already running)
docker compose run app files/stock_prices.csv 14

# Stop all containers
docker compose down
```

### View captured emails

Open [http://localhost:8025](http://localhost:8025) in your browser to see all
emails captured by MailHog.

---

## Sample Commands

```bash
# 1.  Full build + test
mvn clean verify

# 2.  Run locally with interval=7
java -jar target/stock-performance.jar files/stock_prices.csv 7

# 3.  Run locally with interval=30
java -jar target/stock-performance.jar files/stock_prices.csv 30

# 4.  Docker Compose full stack
docker compose up --build

# 5.  Inspect output
cat output/7day.csv | head -20
```

### Expected output format (`7day.csv`)

```
date,symbol,performance
2025-01-29,AAPL,2.8521
2025-01-29,GOOG,-1.2345
2025-01-29,MSFT,0.9876
2025-01-29,NVDA,5.1234
2025-01-29,SPX,1.2300
…
```

---

## Email Configuration

Email settings are controlled via environment variables:

| Variable | Default | Description |
|---|---|---|
| `SMTP_HOST` | `localhost` | SMTP server hostname |
| `SMTP_PORT` | `1025` | SMTP port (1025 = MailHog) |
| `SMTP_AUTH` | `false` | Whether SMTP authentication is required |
| `SMTP_TLS` | `false` | Whether STARTTLS is enabled |
| `SMTP_USER` | _(empty)_ | SMTP username (when auth=true) |
| `SMTP_PASS` | _(empty)_ | SMTP password (when auth=true) |
| `EMAIL_FROM` | `reports@stock-performance.local` | Sender address |
| `EMAIL_FROM_NAME` | `Stock Performance Bot` | Sender display name |
| `EMAIL_TO` | `analyst@example.com` | Recipient address |

**Production SMTP example (e.g. Gmail):**

```bash
SMTP_HOST=smtp.gmail.com \
SMTP_PORT=587 \
SMTP_AUTH=true \
SMTP_TLS=true \
SMTP_USER=you@gmail.com \
SMTP_PASS=app-password \
EMAIL_TO=recipient@example.com \
java -jar target/stock-performance.jar files/stock_prices.csv 7
```

---

## Design Decisions

| Decision | Rationale |
|---|---|
| **No Spring Boot** | The task is a pure CLI batch job. Spring Boot adds significant startup overhead and auto-configuration that would be wasted here. Plain Java 17 + Maven is leaner and more appropriate. |
| **Apache Commons CSV** | Industry-standard library for RFC-4180 CSV parsing; correctly handles quoted fields that contain commas (critical for the `id_stock` column). |
| **BigDecimal for prices and performance** | Eliminates IEEE 754 floating-point rounding errors that would silently corrupt performance percentages. |
| **Records vs custom classes for model** | `StockPrice` and `PerformanceRecord` use hand-written classes rather than Java records to allow constructor-level validation and to keep the domain invariants enforced at construction time. |
| **Trading-day interval via index arithmetic** | Instead of calendar subtraction, the previous price is looked up by array index `i - N`. This naturally skips weekends and holidays because they are absent from the sorted price list. |
| **Always produce 7/14/30 day files** | The requirements explicitly list these three files; the CLI interval is produced additionally so the tool remains flexible for ad-hoc analysis. |
| **Email is best-effort** | SMTP failure must not abort a successful batch run. The CSVs are already written; email is a delivery mechanism, not a correctness requirement. |
| **Multi-stage Docker build** | The build stage uses the full JDK+Maven image; the runtime stage uses `eclipse-temurin:17-jre-alpine` (~85 MB) for a minimal attack surface and fast container start. |
| **SLF4J + Logback** | The SLF4J façade decouples the application code from the logging implementation, making it easy to swap to Log4j2 or java.util.logging without touching business code. |
| **Logback rolling file appender** | Keeps 7 days of logs, preventing unbounded disk usage in long-running scheduled deployments. |

