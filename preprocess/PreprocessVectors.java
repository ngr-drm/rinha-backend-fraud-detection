package preprocess;

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
 * Executar: java preprocess/PreprocessVectors.java <input.json.gz> <output-dir>
 * Exemplo:  java preprocess/PreprocessVectors.java resources/references.json.gz data/
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

        System.out.println("=== Pré-processador de Vetores - Rinha de Backend 2026 ===");
        System.out.println("Input: " + inputPath);
        System.out.println("Output dir: " + outputDir);

        // 1. Carrega vetores do JSON
        System.out.println("\n[1/4] Carregando vetores de " + inputPath + "...");
        long startLoad = System.currentTimeMillis();
        List<VectorRecord> records = loadVectors(inputPath);
        System.out.println("      " + records.size() + " vetores carregados em " + (System.currentTimeMillis() - startLoad) + "ms");

        // 2. Escreve vectors.bin
        String vectorsPath = outputDir + "/vectors.bin";
        System.out.println("\n[2/4] Escrevendo " + vectorsPath + "...");
        long startWrite = System.currentTimeMillis();
        writeVectorsBinary(records, vectorsPath);
        System.out.println("      Escrito em " + (System.currentTimeMillis() - startWrite) + "ms");

        // 3. Constrói VP-Tree
        System.out.println("\n[3/4] Construindo VP-Tree com " + records.size() + " vetores...");
        long startTree = System.currentTimeMillis();
        VPNode root = buildVPTree(records, 0, records.size());
        System.out.println("      Construída em " + (System.currentTimeMillis() - startTree) + "ms");

        // 4. Serializa VP-Tree
        String vptreePath = outputDir + "/vptree.bin";
        System.out.println("\n[4/4] Serializando VP-Tree em " + vptreePath + "...");
        long startSerialize = System.currentTimeMillis();
        int nodeCount = serializeVPTree(root, vptreePath);
        System.out.println("      " + nodeCount + " nós serializados em " + (System.currentTimeMillis() - startSerialize) + "ms");

        // Resumo
        long vectorsSize = Files.size(Path.of(vectorsPath));
        long vptreeSize = Files.size(Path.of(vptreePath));
        System.out.println("\n=== Concluído ===");
        System.out.println("vectors.bin: " + formatBytes(vectorsSize));
        System.out.println("vptree.bin:  " + formatBytes(vptreeSize));
        System.out.println("Total:       " + formatBytes(vectorsSize + vptreeSize));
    }

    /**
     * Carrega vetores do arquivo JSON (gzipado ou não)
     */
    private static List<VectorRecord> loadVectors(String path) throws IOException {
        List<VectorRecord> records = new ArrayList<>();

        InputStream input = Files.newInputStream(Path.of(path));
        if (path.endsWith(".gz")) {
            input = new GZIPInputStream(input);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString();

            // Parser JSON manual simples (evita dependências)
            // Formato: [{"vector": [...], "label": "fraud"}, ...]
            int idx = 0;
            while (true) {
                int vectorStart = json.indexOf("\"vector\"", idx);
                if (vectorStart == -1) break;

                int arrayStart = json.indexOf('[', vectorStart);
                int arrayEnd = json.indexOf(']', arrayStart);
                String vectorStr = json.substring(arrayStart + 1, arrayEnd);

                float[] vector = parseFloatArray(vectorStr);

                int labelStart = json.indexOf("\"label\"", arrayEnd);
                int colonPos = json.indexOf(':', labelStart);
                int quoteStart = json.indexOf('"', colonPos);
                int quoteEnd = json.indexOf('"', quoteStart + 1);
                String label = json.substring(quoteStart + 1, quoteEnd);

                records.add(new VectorRecord(records.size(), vector, "fraud".equals(label)));
                idx = quoteEnd;

                if (records.size() % 500000 == 0) {
                    System.out.println("      ... " + records.size() + " vetores carregados");
                }
            }
        }

        return records;
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
     * Escreve vetores em formato binário
     * Formato: float[14] (56 bytes) + byte label (1 byte) = 57 bytes por registro
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

            // Flush remaining
            if (buffer.position() > 0) {
                buffer.flip();
                channel.write(buffer);
            }
        }
    }

    /**
     * Constrói VP-Tree recursivamente
     */
    private static VPNode buildVPTree(List<VectorRecord> records, int start, int end) {
        if (start >= end) {
            return null;
        }

        if (end - start == 1) {
            // Nó folha
            return new VPNode(records.get(start).index, 0f, null, null);
        }

        // Escolhe vantage point (primeiro elemento do range)
        int vpIdx = start;
        VectorRecord vp = records.get(vpIdx);

        // Calcula distâncias de todos os outros pontos ao vantage point
        List<DistanceRecord> distances = new ArrayList<>(end - start - 1);
        for (int i = start + 1; i < end; i++) {
            float dist = squaredEuclideanDistance(vp.vector, records.get(i).vector);
            distances.add(new DistanceRecord(i, dist));
        }

        if (distances.isEmpty()) {
            return new VPNode(vp.index, 0f, null, null);
        }

        // Ordena por distância e encontra mediana
        distances.sort(Comparator.comparingDouble(d -> d.distance));
        int medianIdx = distances.size() / 2;
        float threshold = distances.get(medianIdx).distance;

        // Particiona: inside (dist <= threshold) e outside (dist > threshold)
        List<VectorRecord> inside = new ArrayList<>();
        List<VectorRecord> outside = new ArrayList<>();

        for (int i = 0; i < distances.size(); i++) {
            int recordIdx = distances.get(i).originalIndex;
            if (i < medianIdx) {
                inside.add(records.get(recordIdx));
            } else {
                outside.add(records.get(recordIdx));
            }
        }

        // Reconstrói listas para recursão
        VPNode insideNode = inside.isEmpty() ? null : buildVPTreeFromList(inside);
        VPNode outsideNode = outside.isEmpty() ? null : buildVPTreeFromList(outside);

        return new VPNode(vp.index, threshold, insideNode, outsideNode);
    }

    private static VPNode buildVPTreeFromList(List<VectorRecord> records) {
        if (records.isEmpty()) {
            return null;
        }

        if (records.size() == 1) {
            return new VPNode(records.get(0).index, 0f, null, null);
        }

        VectorRecord vp = records.get(0);

        List<DistanceRecord> distances = new ArrayList<>(records.size() - 1);
        for (int i = 1; i < records.size(); i++) {
            float dist = squaredEuclideanDistance(vp.vector, records.get(i).vector);
            distances.add(new DistanceRecord(i, dist));
        }

        distances.sort(Comparator.comparingDouble(d -> d.distance));
        int medianIdx = distances.size() / 2;
        float threshold = distances.get(medianIdx).distance;

        List<VectorRecord> inside = new ArrayList<>();
        List<VectorRecord> outside = new ArrayList<>();

        for (int i = 0; i < distances.size(); i++) {
            int recordIdx = distances.get(i).originalIndex;
            if (i < medianIdx) {
                inside.add(records.get(recordIdx));
            } else {
                outside.add(records.get(recordIdx));
            }
        }

        VPNode insideNode = inside.isEmpty() ? null : buildVPTreeFromList(inside);
        VPNode outsideNode = outside.isEmpty() ? null : buildVPTreeFromList(outside);

        return new VPNode(vp.index, threshold, insideNode, outsideNode);
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
     * Serializa VP-Tree em formato binário
     * Usa BFS para garantir offsets contíguos
     */
    private static int serializeVPTree(VPNode root, String path) throws IOException {
        if (root == null) {
            return 0;
        }

        // BFS para coletar nós e calcular offsets
        List<VPNode> nodes = new ArrayList<>();
        Queue<VPNode> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            VPNode node = queue.poll();
            nodes.add(node);
            if (node.inside != null) queue.add(node.inside);
            if (node.outside != null) queue.add(node.outside);
        }

        // Mapeia nó -> offset
        Map<VPNode, Integer> offsetMap = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            offsetMap.put(nodes.get(i), i * NODE_SIZE_BYTES);
        }

        // Escreve
        try (FileChannel channel = FileChannel.open(Path.of(path),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buffer = ByteBuffer.allocate(NODE_SIZE_BYTES * nodes.size());
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            for (VPNode node : nodes) {
                buffer.putInt(node.vpIndex);
                buffer.putFloat(node.threshold);
                buffer.putInt(node.inside != null ? offsetMap.get(node.inside) : OFFSET_NULL);
                buffer.putInt(node.outside != null ? offsetMap.get(node.outside) : OFFSET_NULL);
            }

            buffer.flip();
            channel.write(buffer);
        }

        return nodes.size();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    // Records
    record VectorRecord(int index, float[] vector, boolean isFraud) {}
    record DistanceRecord(int originalIndex, float distance) {}

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

