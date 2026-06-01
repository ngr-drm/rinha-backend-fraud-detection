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
     * Busca os k vizinhos mais próximos usando a VP-Tree
     *
     * @param query Vetor de consulta (14 dimensões)
     * @param k Número de vizinhos desejados
     * @return Array de k vizinhos mais próximos
     */
    public Neighbor[] findKNearest(float[] query, int k) {
        if (!isReady()) {
            // Fallback: brute force se árvore não carregada
            return bruteForceKNN(query, k);
        }

        // Max-heap para manter os k mais próximos (ordenado por distância decrescente)
        PriorityQueue<Neighbor> heap = new PriorityQueue<>(k, (a, b) -> Float.compare(b.distance, a.distance));

        // Busca recursiva na árvore
        searchNode(0, query, k, heap, Float.MAX_VALUE);

        // Converte heap para array ordenado por distância crescente
        Neighbor[] result = new Neighbor[heap.size()];
        for (int i = result.length - 1; i >= 0; i--) {
            result[i] = heap.poll();
        }

        return result;
    }

    /**
     * Busca recursiva na VP-Tree
     *
     * Nota: Usamos squared euclidean distance internamente, mas o threshold
     * foi calculado com squared distance no pré-processamento, então as
     * comparações são consistentes.
     */
    private float searchNode(int nodeOffset, float[] query, int k, PriorityQueue<Neighbor> heap, float tau) {
        if (nodeOffset == OFFSET_NULL || nodeOffset < 0 || nodeOffset >= nodeCount * NODE_SIZE_BYTES) {
            return tau;
        }

        // Leitura absoluta do nó (zero-alocação, thread-safe)
        final MappedByteBuffer buf = this.treeBuffer;
        int vpIndex        = buf.getInt(nodeOffset);
        float threshold    = buf.getFloat(nodeOffset + 4);   // squared distance
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

        // Folha
        if (insideOffset == OFFSET_NULL && outsideOffset == OFFSET_NULL) {
            return tau;
        }

        // Pré-calcula sqrts uma única vez (regra do triângulo em espaço euclidiano linear)
        // Critério exato: |sqrt(distSq) - sqrt(threshold)| < sqrt(tau)  → visitar lado oposto
        float sqrtDist      = (float) Math.sqrt(distSq);
        float sqrtThreshold = (float) Math.sqrt(threshold);

        if (distSq < threshold) {
            // Visita inside primeiro
            if (insideOffset != OFFSET_NULL) {
                tau = searchNode(insideOffset, query, k, heap, tau);
            }
            // Re-avalia poda do outside com tau possivelmente reduzido
            if (outsideOffset != OFFSET_NULL) {
                float sqrtTau = (float) Math.sqrt(tau);
                if (sqrtDist + sqrtTau >= sqrtThreshold) {
                    tau = searchNode(outsideOffset, query, k, heap, tau);
                }
            }
        } else {
            // Visita outside primeiro
            if (outsideOffset != OFFSET_NULL) {
                tau = searchNode(outsideOffset, query, k, heap, tau);
            }
            if (insideOffset != OFFSET_NULL) {
                float sqrtTau = (float) Math.sqrt(tau);
                if (sqrtDist - sqrtTau <= sqrtThreshold) {
                    tau = searchNode(insideOffset, query, k, heap, tau);
                }
            }
        }

        return tau;
    }

    /**
     * Fallback: brute force k-NN quando árvore não está disponível
     */
    private Neighbor[] bruteForceKNN(float[] query, int k) {
        if (!vectorStore.isReady()) {
            return new Neighbor[0];
        }

        // Max-heap
        PriorityQueue<Neighbor> heap = new PriorityQueue<>(k, (a, b) -> Float.compare(b.distance, a.distance));

        int count = vectorStore.getVectorCount();
        for (int i = 0; i < count; i++) {
            float dist = vectorStore.squaredEuclideanDistance(i, query);

            if (heap.size() < k) {
                heap.add(new Neighbor(i, dist, vectorStore.isFraud(i)));
            } else if (dist < heap.peek().distance) {
                heap.poll();
                heap.add(new Neighbor(i, dist, vectorStore.isFraud(i)));
            }
        }

        // Converte para array ordenado
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


