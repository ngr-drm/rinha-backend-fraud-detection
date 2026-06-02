package com.rb.fraud.domain;

import com.rb.fraud.api.ReadyController;
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
import java.util.PriorityQueue;

/**
 * VP-Tree (Vantage-Point Tree) para busca k-NN exata
 * Conforme especificado em AGENTS.md seção 6
 *
 * A árvore é pré-construída no build do Docker e serializada em vptree.bin.
 * No startup, apenas carregamos via mmap e reconstruímos os ponteiros.
 *
 * Formato de serialização por nó (16 bytes):
 * - int32 vantagePointIndex
 * - float32 threshold
 * - int32 insideOffset (-1 se folha)
 * - int32 outsideOffset (-1 se folha)
 */
@Component
public class VPTree {

    private static final Logger log = LoggerFactory.getLogger(VPTree.class);

    private static final int NODE_SIZE_BYTES = 16;
    private static final int OFFSET_NULL = -1;

    @Value("${app.data.vptree-path:/data/vptree.bin}")
    private String vptreePath;

    @Value("${app.search.max-nodes:8000}")
    private int maxNodesToVisit;

    @Value("${app.search.max-micros:1200}")
    private int maxSearchMicros;

    private final VectorStore vectorStore;

    private MappedByteBuffer treeBuffer;
    private int nodeCount;

    public VPTree(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void init() throws IOException {
        Path path = Path.of(vptreePath);

        if (!Files.exists(path)) {
            log.warn("vptree.bin não encontrado em {}. VPTree não inicializada.", vptreePath);
            return;
        }

        long fileSize = Files.size(path);
        this.nodeCount = (int) (fileSize / NODE_SIZE_BYTES);

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = file.getChannel()) {

            this.treeBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            this.treeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        log.info("VPTree inicializada: {} nós carregados via mmap", nodeCount);

        // Marca API como pronta após carregar VectorStore + VPTree
        if (vectorStore.isReady() && isReady()) {
            ReadyController.markReady();
            log.info("API marcada como ready");
        }
    }

    /**
     * Busca os k vizinhos mais próximos usando a VP-Tree (ITERATIVO)
     *
     * @param query Vetor de consulta (14 dimensões)
     * @param k Número de vizinhos desejados
     * @return Array de k vizinhos mais próximos
     */
    public Neighbor[] findKNearest(float[] query, int k) {
        if (!isReady()) {
            return new Neighbor[0];
        }

        final MappedByteBuffer buf = this.treeBuffer;
        final int maxOffset = nodeCount * NODE_SIZE_BYTES;
        final long deadlineNanos = System.nanoTime() + (long) Math.max(100, maxSearchMicros) * 1000L;

        // Max-heap para manter os k mais próximos
        PriorityQueue<Neighbor> heap = new PriorityQueue<>(k, (a, b) -> Float.compare(b.distance, a.distance));

        // Pilha maior + guarda de capacidade para evitar overflow em cenários de backtracking.
        int[] stack = new int[1024];
        int stackPtr = 0;
        stack[stackPtr++] = 0; // Começa na raiz

        float tau = Float.MAX_VALUE;
        int nodesVisited = 0;

        while (stackPtr > 0 && nodesVisited < maxNodesToVisit && System.nanoTime() < deadlineNanos) {
            int nodeOffset = stack[--stackPtr];

            if (nodeOffset == OFFSET_NULL || nodeOffset < 0 || nodeOffset >= maxOffset) {
                continue;
            }

            nodesVisited++;

            // Leitura do nó
            int vpIndex        = buf.getInt(nodeOffset);
            float threshold    = buf.getFloat(nodeOffset + 4);
            int insideOffset   = buf.getInt(nodeOffset + 8);
            int outsideOffset  = buf.getInt(nodeOffset + 12);

            // Distância (squared) ao vantage point
            float distSq = vectorStore.squaredEuclideanDistance(vpIndex, query);

            // Atualiza heap
            if (heap.size() < k) {
                heap.add(new Neighbor(vpIndex, distSq, vectorStore.isFraud(vpIndex)));
                if (heap.size() == k) {
                    tau = heap.peek().distance;
                }
            } else if (distSq < tau) {
                heap.poll();
                heap.add(new Neighbor(vpIndex, distSq, vectorStore.isFraud(vpIndex)));
                tau = heap.peek().distance;
            }

            // Folha: continua para próximo nó na pilha
            if (insideOffset == OFFSET_NULL && outsideOffset == OFFSET_NULL) {
                continue;
            }

            // Poda usando regra do triângulo
            // sqrt é caro, mas necessário para poda correta
            float sqrtDist      = (float) Math.sqrt(distSq);
            float sqrtThreshold = (float) Math.sqrt(threshold);
            float sqrtTau       = (float) Math.sqrt(tau);

            // Empilha nós a visitar (ordem importa: visitamos o primeiro empilhado por último)
            if (distSq < threshold) {
                // Query dentro da esfera - inside é mais promissor
                // Empilha outside primeiro (será visitado depois se necessário)
                if (outsideOffset != OFFSET_NULL && sqrtDist + sqrtTau >= sqrtThreshold && stackPtr < stack.length) {
                    stack[stackPtr++] = outsideOffset;
                }
                // Empilha inside (será visitado primeiro)
                if (insideOffset != OFFSET_NULL && stackPtr < stack.length) {
                    stack[stackPtr++] = insideOffset;
                }
            } else {
                // Query fora da esfera - outside é mais promissor
                if (insideOffset != OFFSET_NULL && sqrtDist - sqrtTau <= sqrtThreshold && stackPtr < stack.length) {
                    stack[stackPtr++] = insideOffset;
                }
                if (outsideOffset != OFFSET_NULL && stackPtr < stack.length) {
                    stack[stackPtr++] = outsideOffset;
                }
            }
        }

        // Converte heap para array ordenado por distância crescente
        Neighbor[] result = new Neighbor[heap.size()];
        for (int i = result.length - 1; i >= 0; i--) {
            result[i] = heap.poll();
        }

        return result;
    }

    /**
     * Verifica se a árvore está pronta para uso
     */
    public boolean isReady() {
        return treeBuffer != null && nodeCount > 0;
    }

    /**
     * Representa um vizinho encontrado na busca
     */
    public record Neighbor(int index, float distance, boolean isFraud) {}
}
