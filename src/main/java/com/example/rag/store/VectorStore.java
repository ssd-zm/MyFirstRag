package com.example.rag.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存向量库 + JSON 持久化。
 * 检索 = 暴力余弦相似度扫描，语料到几万 chunk 之前完全够用；
 * 以后要换 pgvector/ES，只需保持 addAll/search 两个方法签名不变。
 * <p>
 * 余弦相似度手写（点积 / 两个范数的乘积）：必须知道检索底层是什么，
 * query 的范数只算一次，避免每个 chunk 重复计算。
 */
public class VectorStore {

    /** JSON 读写用的 Jackson 实例，只做数据绑定；此类无状态配置，多个实例共用同一份即可 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 全量切片索引，内存中线性存放，检索时逐个扫过；未建 ANN/倒排，几万条以内够用 */
    private final List<Chunk> chunks = new ArrayList<>();

    public void addAll(List<Chunk> list) {
        chunks.addAll(list);
    }

    public int size() {
        return chunks.size();
    }

    /** 返回相似度最高的 k 条，score 降序 */
    public List<Scored> search(float[] query, int k) {
        double qNorm = norm(query);
        if (qNorm == 0) return List.of();   // 空向量直接返回，避免除零
        List<Scored> scored = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            if (c.vector() == null) continue;
            scored.add(new Scored(c, cosine(query, qNorm, c.vector())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.subList(0, Math.min(k, scored.size()));
    }

    private static double cosine(float[] a, double aNorm, float[] b) {
        double dot = 0, bNorm = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            bNorm += b[i] * b[i];
        }
        if (bNorm == 0) return 0;
        return dot / (aNorm * Math.sqrt(bNorm));
    }

    private static double norm(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        return Math.sqrt(n);
    }

    // ---------- 持久化: data/index.json，二次启动不重建索引 ----------

    public void save(Path file) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("chunks");
        for (Chunk c : chunks) {
            ObjectNode n = arr.addObject();
            n.put("id", c.id());
            n.put("sourcePath", c.sourcePath());
            n.put("lineFrom", c.lineFrom());
            n.put("lineTo", c.lineTo());
            n.put("text", c.text());
            ArrayNode v = n.putArray("vector");
            for (float x : c.vector()) v.add(x);
        }
        try {
            MAPPER.writeValue(file.toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("保存索引失败: " + e.getMessage(), e);
        }
    }

    public static VectorStore load(Path file) {
        VectorStore store = new VectorStore();
        if (!Files.exists(file)) return store;
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            for (JsonNode n : root.withArray("chunks")) {
                JsonNode vec = n.path("vector");
                float[] v = new float[vec.size()];
                for (int i = 0; i < v.length; i++) v[i] = (float) vec.get(i).asDouble();
                store.chunks.add(new Chunk(
                        n.path("id").asText(),
                        n.path("sourcePath").asText(),
                        n.path("lineFrom").asInt(),
                        n.path("lineTo").asInt(),
                        n.path("text").asText(),
                        v));
            }
            return store;
        } catch (IOException e) {
            throw new IllegalStateException("加载索引失败: " + e.getMessage(), e);
        }
    }
}
