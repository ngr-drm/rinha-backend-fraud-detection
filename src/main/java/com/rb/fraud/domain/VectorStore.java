package com.rb.fraud.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.RandomAccessFile;
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
        // HOT PATH: chamado N vezes por query k-NN.
        // Usa leituras absolutas no MappedByteBuffer (zero-alocação, thread-safe).
        final MappedByteBuffer buf = this.buffer;
        final int offset = index * RECORD_SIZE_BYTES;

        // Loop unrolled para 14 dimensões (evita overhead de loop + permite SIMD do JIT)
        float d0  = buf.getFloat(offset)      - query[0];
        float d1  = buf.getFloat(offset + 4)  - query[1];
        float d2  = buf.getFloat(offset + 8)  - query[2];
        float d3  = buf.getFloat(offset + 12) - query[3];
        float d4  = buf.getFloat(offset + 16) - query[4];
        float d5  = buf.getFloat(offset + 20) - query[5];
        float d6  = buf.getFloat(offset + 24) - query[6];
        float d7  = buf.getFloat(offset + 28) - query[7];
        float d8  = buf.getFloat(offset + 32) - query[8];
        float d9  = buf.getFloat(offset + 36) - query[9];
        float d10 = buf.getFloat(offset + 40) - query[10];
        float d11 = buf.getFloat(offset + 44) - query[11];
        float d12 = buf.getFloat(offset + 48) - query[12];
        float d13 = buf.getFloat(offset + 52) - query[13];

        return d0*d0 + d1*d1 + d2*d2 + d3*d3 + d4*d4 + d5*d5 + d6*d6
             + d7*d7 + d8*d8 + d9*d9 + d10*d10 + d11*d11 + d12*d12 + d13*d13;
    }

    /**
     * Verifica se o store está pronto para uso
     */
    public boolean isReady() {
        return buffer != null && vectorCount > 0;
    }
}

