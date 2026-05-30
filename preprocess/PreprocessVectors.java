import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Pré-processador de vetores para a Rinha de Backend 2026
 *
 * Lê references.json.gz e gera:
 * - vectors.bin: vetores em formato binário (float[14] + byte label)
 * - vptree.bin: VP-Tree serializada para busca k-NN
 *
 * Executar: java PreprocessVectors.java <input.json.gz> <output-dir>
 */
public class PreprocessVectors {

    private static final int VECTOR_DIMENSIONS = 14;
    private static final int BYTES_PER_FLOAT = 4;
    private static final int VECTOR_SIZE_BYTES = VECTOR_DIMENSIONS * BYTES_PER_FLOAT; // 56 bytes
    private static final int LABEL_SIZE_BYTES = 1;
    private static final int RECORD_SIZE_BYTES = VECTOR_SIZE_BYTES + LABEL_SIZE_BYTES; // 57 bytes

    // VP-Tree node size: vpIndex(4) + threshold(4) + insideOffset(4) + outsideOffset(4) = 16 bytes
    private static final int NODE_SIZE_BYTES = 16;
    private static final int OFFSET_NULL = -1;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: java PreprocessVectors.java <input.json.gz> <output-dir>");
            System.out.println("Exemplo: java PreprocessVectors.java resources/references.json.gz data/");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputDir = args[1];

        Files.createDirectories(Path.of(outputDir));

        System.out.println("=== Pre-processador de Vetores - Rinha de Backend 2026 ===");
        System.out.println("Input: " + inputPath);
        System.out.println("Output dir: " + outputDir);
        System.out.flush();

        // 1. Carrega vetores do JSON
        System.out.println("\n[1/4] Carregando vetores de " + inputPath + "...");
        System.out.flush();
        long startLoad = System.currentTimeMillis();
        List<VectorRecord> records = loadVectorsStreaming(inputPath);
        System.out.println("      " + records.size() + " vetores carregados em " + (System.currentTimeMillis() - startLoad) + "ms");
        System.out.flush();

        // 2. Escreve vectors.bin
        String vectorsPath = outputDir + "/vectors.bin";
        System.out.println("\n[2/4] Escrevendo " + vectorsPath + "...");
        System.out.flush();
        long startWrite = System.currentTimeMillis();
        writeVectorsBinary(records, vectorsPath);
        System.out.println("      Escrito em " + (System.currentTimeMillis() - startWrite) + "ms");
        System.out.flush();

        // 3. Constroi VP-Tree
        System.out.println("\n[3/4] Construindo VP-Tree com " + records.size() + " vetores...");
        System.out.flush();
        long startTree = System.currentTimeMillis();

        // Cria array de indices para construcao iterativa
        int[] indices = new int[records.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }

        VPNode root = buildVPTreeIterative(records, indices);
        System.out.println("      Construida em " + (System.currentTimeMillis() - startTree) + "ms");
        System.out.flush();

        // 4. Serializa VP-Tree
        String vptreePath = outputDir + "/vptree.bin";
        System.out.println("\n[4/4] Serializando VP-Tree em " + vptreePath + "...");
        System.out.flush();
        long startSerialize = System.currentTimeMillis();
        int nodeCount = serializeVPTree(root, vptreePath);
        System.out.println("      " + nodeCount + " nos serializados em " + (System.currentTimeMillis() - startSerialize) + "ms");
        System.out.flush();

        // Resumo
        long vectorsSize = Files.size(Path.of(vectorsPath));
        long vptreeSize = Files.size(Path.of(vptreePath));
        System.out.println("\n=== Concluido ===");
        System.out.println("vectors.bin: " + formatBytes(vectorsSize));
        System.out.println("vptree.bin:  " + formatBytes(vptreeSize));
        System.out.println("Total:       " + formatBytes(vectorsSize + vptreeSize));
        System.out.flush();
    }

    /**
     * Carrega vetores do arquivo JSON usando streaming (baixo uso de memoria)
     */
    private static List<VectorRecord> loadVectorsStreaming(String path) throws IOException {
        List<VectorRecord> records = new ArrayList<>(3_100_000);

        InputStream input = Files.newInputStream(Path.of(path));
        if (path.endsWith(".gz")) {
            input = new GZIPInputStream(input, 65536);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input), 1024 * 1024)) {
            StringBuilder sb = new StringBuilder(500);
            int ch;
            int braceCount = 0;
            boolean inObject = false;

            while ((ch = reader.read()) != -1) {
                char c = (char) ch;

                if (c == '{') {
                    braceCount++;
                    inObject = true;
                    sb.append(c);
                } else if (c == '}') {
                    braceCount--;
                    sb.append(c);

                    if (braceCount == 0 && inObject) {
                        // Objeto completo
                        String obj = sb.toString();
                        VectorRecord record = parseObject(obj, records.size());
                        if (record != null) {
                            records.add(record);
                            if (records.size() % 500000 == 0) {
                                System.out.println("      ... " + records.size() + " vetores carregados");
                                System.out.flush();
                            }
                        }
                        sb.setLength(0);
                        inObject = false;
                    }
                } else if (inObject) {
                    sb.append(c);
                }
            }
        }

        return records;
    }

    /**
     * Parse de um objeto JSON individual
     */
    private static VectorRecord parseObject(String obj, int index) {
        try {
            // Encontra o array de vector
            int vectorStart = obj.indexOf('[');
            int vectorEnd = obj.indexOf(']');
            if (vectorStart == -1 || vectorEnd == -1) return null;

            String vectorStr = obj.substring(vectorStart + 1, vectorEnd);
            float[] vector = parseFloatArray(vectorStr);
            if (vector.length != VECTOR_DIMENSIONS) return null;

            // Encontra o label
            boolean isFraud = obj.contains("\"fraud\"");

            return new VectorRecord(index, vector, isFraud);
        } catch (Exception e) {
            return null;
        }
    }

    private static float[] parseFloatArray(String str) {
        String[] parts = str.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    /**
     * Escreve vetores em formato binario
     */
    private static void writeVectorsBinary(List<VectorRecord> records, String path) throws IOException {
        try (FileChannel channel = FileChannel.open(Path.of(path),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buffer = ByteBuffer.allocate(RECORD_SIZE_BYTES * 10000);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            int count = 0;
            for (VectorRecord record : records) {
                for (float f : record.vector) {
                    buffer.putFloat(f);
                }
                buffer.put((byte) (record.isFraud ? 1 : 0));

                count++;
                if (count % 10000 == 0) {
                    buffer.flip();
                    channel.write(buffer);
                    buffer.clear();
                }
            }

            if (buffer.position() > 0) {
                buffer.flip();
                channel.write(buffer);
            }
        }
    }

    /**
     * Constroi VP-Tree de forma iterativa (evita stack overflow)
     */
    private static VPNode buildVPTreeIterative(List<VectorRecord> records, int[] indices) {
        if (indices.length == 0) return null;

        // Usa uma pilha explicita para evitar recursao profunda
        Deque<BuildTask> stack = new ArrayDeque<>();
        Map<BuildTask, VPNode> results = new HashMap<>();

        BuildTask rootTask = new BuildTask(indices, null, false);
        stack.push(rootTask);

        Random random = new Random(42); // Seed fixo para reproducibilidade

        while (!stack.isEmpty()) {
            BuildTask task = stack.peek();

            if (task.indices.length == 0) {
                results.put(task, null);
                stack.pop();
                continue;
            }

            if (task.indices.length == 1) {
                VPNode node = new VPNode(task.indices[0], 0f, null, null);
                results.put(task, node);
                stack.pop();
                continue;
            }

            // Verifica se os filhos ja foram processados
            if (task.insideTask != null && task.outsideTask != null
                && results.containsKey(task.insideTask) && results.containsKey(task.outsideTask)) {
                VPNode inside = results.get(task.insideTask);
                VPNode outside = results.get(task.outsideTask);
                VPNode node = new VPNode(task.vpIndex, task.threshold, inside, outside);
                results.put(task, node);
                stack.pop();
                continue;
            }

            // Primeira visita: calcular particao
            if (task.vpIndex == -1) {
                // Escolhe vantage point (aleatorio para melhor balanceamento)
                int vpLocalIdx = random.nextInt(task.indices.length);
                task.vpIndex = task.indices[vpLocalIdx];
                float[] vpVector = records.get(task.vpIndex).vector;

                // Calcula distancias
                float[] distances = new float[task.indices.length - 1];
                int[] otherIndices = new int[task.indices.length - 1];
                int j = 0;
                for (int i = 0; i < task.indices.length; i++) {
                    if (i != vpLocalIdx) {
                        otherIndices[j] = task.indices[i];
                        distances[j] = squaredEuclideanDistance(vpVector, records.get(task.indices[i]).vector);
                        j++;
                    }
                }

                if (otherIndices.length == 0) {
                    VPNode node = new VPNode(task.vpIndex, 0f, null, null);
                    results.put(task, node);
                    stack.pop();
                    continue;
                }

                // Ordena por distancia usando indices
                Integer[] sortedIdx = new Integer[distances.length];
                for (int i = 0; i < sortedIdx.length; i++) sortedIdx[i] = i;
                final float[] finalDistances = distances;
                Arrays.sort(sortedIdx, (a, b) -> Float.compare(finalDistances[a], finalDistances[b]));

                int medianIdx = sortedIdx.length / 2;
                task.threshold = distances[sortedIdx[medianIdx]];

                // Particiona
                int[] insideIndices = new int[medianIdx];
                int[] outsideIndices = new int[sortedIdx.length - medianIdx];

                for (int i = 0; i < medianIdx; i++) {
                    insideIndices[i] = otherIndices[sortedIdx[i]];
                }
                for (int i = medianIdx; i < sortedIdx.length; i++) {
                    outsideIndices[i - medianIdx] = otherIndices[sortedIdx[i]];
                }

                // Cria tarefas filhas
                task.insideTask = new BuildTask(insideIndices, task, false);
                task.outsideTask = new BuildTask(outsideIndices, task, true);

                stack.push(task.outsideTask);
                stack.push(task.insideTask);
            }
        }

        return results.get(rootTask);
    }

    private static float squaredEuclideanDistance(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }

    /**
     * Serializa VP-Tree em formato binario usando BFS
     */
    private static int serializeVPTree(VPNode root, String path) throws IOException {
        if (root == null) {
            // Cria arquivo vazio
            Files.write(Path.of(path), new byte[0]);
            return 0;
        }

        // BFS para coletar nos
        List<VPNode> nodes = new ArrayList<>();
        Queue<VPNode> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            VPNode node = queue.poll();
            nodes.add(node);
            if (node.inside != null) queue.add(node.inside);
            if (node.outside != null) queue.add(node.outside);
        }

        // Mapeia no -> offset
        Map<VPNode, Integer> offsetMap = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            offsetMap.put(nodes.get(i), i * NODE_SIZE_BYTES);
        }

        // Escreve em chunks para evitar OutOfMemory
        try (FileChannel channel = FileChannel.open(Path.of(path),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            int chunkSize = 100000;
            ByteBuffer buffer = ByteBuffer.allocate(NODE_SIZE_BYTES * Math.min(chunkSize, nodes.size()));
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            int count = 0;
            for (VPNode node : nodes) {
                buffer.putInt(node.vpIndex);
                buffer.putFloat(node.threshold);
                buffer.putInt(node.inside != null ? offsetMap.get(node.inside) : OFFSET_NULL);
                buffer.putInt(node.outside != null ? offsetMap.get(node.outside) : OFFSET_NULL);

                count++;
                if (count % chunkSize == 0) {
                    buffer.flip();
                    channel.write(buffer);
                    buffer.clear();
                }
            }

            if (buffer.position() > 0) {
                buffer.flip();
                channel.write(buffer);
            }
        }

        return nodes.size();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    // Classes auxiliares
    static class VectorRecord {
        final int index;
        final float[] vector;
        final boolean isFraud;

        VectorRecord(int index, float[] vector, boolean isFraud) {
            this.index = index;
            this.vector = vector;
            this.isFraud = isFraud;
        }
    }

    static class BuildTask {
        final int[] indices;
        final BuildTask parent;
        final boolean isOutside;
        int vpIndex = -1;
        float threshold;
        BuildTask insideTask;
        BuildTask outsideTask;

        BuildTask(int[] indices, BuildTask parent, boolean isOutside) {
            this.indices = indices;
            this.parent = parent;
            this.isOutside = isOutside;
        }
    }

    static class VPNode {
        final int vpIndex;
        final float threshold;
        final VPNode inside;
        final VPNode outside;

        VPNode(int vpIndex, float threshold, VPNode inside, VPNode outside) {
            this.vpIndex = vpIndex;
            this.threshold = threshold;
            this.inside = inside;
            this.outside = outside;
        }
    }
}
