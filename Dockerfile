# =============================================================================
# Rinha de Backend 2026 - Dockerfile Multi-stage
# =============================================================================
# Stage 1: Pré-processamento (converte references.json.gz → vectors.bin + vptree.bin)
# Stage 2: Build Maven + GraalVM Native Image
# Stage 3: Runtime mínimo
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Pré-processamento dos vetores
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS preprocess

WORKDIR /preprocess

# Copia o script de pré-processamento e os dados
COPY preprocess/PreprocessVectors.java ./PreprocessVectors.java
COPY resources/references.json.gz ./references.json.gz

# Cria diretório de output e executa o pré-processador
# Usando java source-file mode com heap aumentado para processar 3M vetores
RUN mkdir -p /data && \
    java -Xmx4g -Xms1g PreprocessVectors.java references.json.gz /data

# Output: /data/vectors.bin e /data/vptree.bin

# -----------------------------------------------------------------------------
# Stage 2: Build da aplicação Spring Boot (JIT por enquanto, Native depois)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copia arquivos do Maven
COPY pom.xml ./
COPY src ./src

# Instala Maven e faz build
RUN apt-get update && apt-get install -y maven && \
    mvn package -DskipTests -q

# Output: /app/target/fraud-detection-1.0.0.jar

# -----------------------------------------------------------------------------
# Stage 3: Runtime mínimo
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Copia o JAR
COPY --from=build /app/target/fraud-detection-1.0.0.jar ./app.jar

# Copia os dados pré-processados
COPY --from=preprocess /data/vectors.bin /data/vectors.bin
COPY --from=preprocess /data/vptree.bin /data/vptree.bin

# Copia mcc_risk.json (usado no runtime se necessário)
COPY resources/mcc_risk.json /data/mcc_risk.json

# Configuração JVM otimizada para baixa memória
# SerialGC é mais eficiente que G1 em heaps pequenos
# TieredCompilation=1 acelera startup sacrificando otimização de longo prazo
ENV JAVA_OPTS="-Xms80m -Xmx120m -XX:+UseSerialGC -XX:+TieredCompilation -XX:TieredStopAtLevel=1"

EXPOSE 8080

# Health check
HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/ready || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

