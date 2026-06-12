# ─────────────────────────────────────────────────────────────────────────────
#  Stage 1 – Build
#  Uses a full JDK + Maven image to compile and package the fat jar.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.7-eclipse-temurin-17 AS build

WORKDIR /workspace

# Copy pom first so Maven dependency layer is cached independently of source.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy sources and build (skip tests during image build – run them separately).
COPY src ./src
RUN mvn package -DskipTests -q

# ────────────────────────────────────────────────────��────────────────────────
#  Stage 2 – Runtime
#  Slim JRE image; only the fat jar and input data are copied.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

LABEL maintainer="Stock Performance Calculator"
LABEL description="Calculates N-day trading performance from historical stock prices."

WORKDIR /app

# Copy the fat jar produced in the build stage.
COPY --from=build /workspace/target/stock-performance.jar ./stock-performance.jar

# Copy the default input data files so the container is self-contained.
COPY files/ ./files/

# Create the output directory with appropriate permissions.
RUN mkdir -p /app/output && chmod 755 /app/output

# ── Environment variables (override at runtime) ──────────────────────────────
ENV SMTP_HOST=mailhog          \
    SMTP_PORT=1025              \
    SMTP_AUTH=false             \
    SMTP_TLS=false              \
    EMAIL_FROM=reports@stock-performance.local \
    EMAIL_FROM_NAME="Stock Performance Bot"    \
    EMAIL_TO=karirasaumya@gmail.com

# Default: compute 7-day performance.
# Override with:  docker run ... <image> files/stock_prices.csv 30
ENTRYPOINT ["java", "-jar", "stock-performance.jar"]
CMD ["files/stock_prices.csv", "7"]

