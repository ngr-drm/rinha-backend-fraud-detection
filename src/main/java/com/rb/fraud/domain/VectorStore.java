package com.rb.fraud.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Armazena vetores de referência via memory-mapped file
 * Conforme especificado em AGENTS.md seção 5
 *
 * Formato binário (vectors.bin):
 * - Cada registro: float[14] (56 bytes) + byte label (1 byte) = 57 bytes
 * - Total: 3.000.000 × 57 = ~163 MB
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private static final int VECTOR_DIMENSIONS = 14;
    private static final int BYTES_PER_FLOAT = 4;
    private static final int VECTOR_SIZE_BYTES = VECTOR_DIMENSIONS * BYTES_PER_FLOAT; // 56 bytes
    private static final int LABEL_SIZE_BYTES = 1;
    private static final int RECORD_SIZE_BYTES = VECTOR_SIZE_BYTES + LABEL_SIZE_BYTES; // 57 bytes

    @Value("${app.data.vectors-path:/data/vectors.bin}")
    private String vectorsPath;

    private MappedByteBuffer buffer;
    private int vectorCount;

    @PostConstruct
    public void init() throws IOException {
        Path path = Path.of(vectorsPath);

        if (!Files.exists(path)) {
            log.warn("vectors.bin não encontrado em {}. VectorStore não inicializado.", vectorsPath);
            return;
        }

        long fileSize = Files.size(path);
        this.vectorCount = (int) (fileSize / RECORD_SIZE_BYTES);

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = file.getChannel()) {

            this.buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            this.buffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        log.info("VectorStore inicializado: {} vetores carregados via mmap", vectorCount);
    }

    /**
     * Retorna o número total de vetores
     */
    public int getVectorCount() {
        return vectorCount;
    }

    /**
     * Lê o vetor no índice especificado
     *
     * @param index Índice do vetor (0-based)
     * @return float[14] com os valores do vetor
     */
    public float[] getVector(int index) {
        if (buffer == null) {
            throw new IllegalStateException("VectorStore não inicializado");
        }
        if (index < 0 || index >= vectorCount) {
            throw new IndexOutOfBoundsException("Índice " + index + " fora do range [0, " + vectorCount + ")");
        }

        float[] vector = new float[VECTOR_DIMENSIONS];
        int offset = index * RECORD_SIZE_BYTES;

        // Thread-safe: cada thread usa sua própria posição via slice
        ByteBuffer slice = buffer.slice(offset, VECTOR_SIZE_BYTES);
        slice.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < VECTOR_DIMENSIONS; i++) {
            vector[i] = slice.getFloat();
        }

        return vector;
    }

    /**
     * Retorna o label do vetor (0 = legit, 1 = fraud)
     *
     * @param index Índice do vetor (0-based)
     * @return true se fraud, false se legit
     */
    public boolean isFraud(int index) {
        if (buffer == null) {
            throw new IllegalStateException("VectorStore não inicializado");
        }
        if (index < 0 || index >= vectorCount) {
            throw new IndexOutOfBoundsException("Índice " + index + " fora do range [0, " + vectorCount + ")");
        }

        int offset = index * RECORD_SIZE_BYTES + VECTOR_SIZE_BYTES;
        return buffer.get(offset) == 1;
    }

    /**
     * Calcula distância euclidiana ao quadrado (sem sqrt para performance)
     * Conforme ADR D5 em AGENTS.md
     *
     * @param index Índice do vetor de referência
     * @param query Vetor de consulta
     * @return Distância euclidiana ao quadrado
     */
    public float squaredEuclideanDistance(int index, float[] query) {
        if (buffer == null) {
            throw new IllegalStateException("VectorStore não inicializado");
        }

        int offset = index * RECORD_SIZE_BYTES;
        ByteBuffer slice = buffer.slice(offset, VECTOR_SIZE_BYTES);
        slice.order(ByteOrder.LITTLE_ENDIAN);

        float sum = 0f;
        for (int i = 0; i < VECTOR_DIMENSIONS; i++) {
            float diff = slice.getFloat() - query[i];
            sum += diff * diff;
        }

        return sum;
    }

    /**
     * Verifica se o store está pronto para uso
     */
    public boolean isReady() {
        return buffer != null && vectorCount > 0;
    }
}

