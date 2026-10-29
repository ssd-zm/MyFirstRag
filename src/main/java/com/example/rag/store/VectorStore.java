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

    // 读写 JSON 用的工具，static 表示整个类共用一份
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 所有切片都放在这个 List 里，检索时一条条扫过去
    private final List<Chunk> chunks = new ArrayList<>();

    public void addAll(List<Chunk> list) {
        // 把一批切片直接加进库里
        chunks.addAll(list);
    }

    public int size() {
        // 库里现在一共有多少条切片
        return chunks.size();
    }

    /** 返回相似度最高的 k 条，score 降序 */
    public List<Scored> search(float[] query, int k) {
        // 先算问题向量本身的长度，后面每条切片都要用，算一次就够
        double qNorm = norm(query);
        // 空向量没法算相似度，直接返回空结果，避免除以零
        if (qNorm == 0) return List.of();
        // 准备装每条切片的得分
        List<Scored> scored = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            // 还没算过向量的切片先跳过
            if (c.vector() == null) continue;
            // 算这条切片和问题的相似度
            scored.add(new Scored(c, cosine(query, qNorm, c.vector())));
        }
        // 按分数从高到低排好序
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        // 只取前 k 条，k 比总数大时就全取
        return scored.subList(0, Math.min(k, scored.size()));
    }

    private static double cosine(float[] a, double aNorm, float[] b) {
        // dot 是点积，bNorm 是 b 每个数平方的和
        double dot = 0, bNorm = 0;
        for (int i = 0; i < a.length; i++) {
            // 对应位置相乘再累加，就是点积
            dot += a[i] * b[i];
            // 顺手把 b 的平方和也算出来
            bNorm += b[i] * b[i];
        }
        // b 全是 0 就没法比方向，返回 0
        if (bNorm == 0) return 0;
        // 余弦相似度 = 点积 / (a 的长度 × b 的长度)
        return dot / (aNorm * Math.sqrt(bNorm));
    }

    private static double norm(float[] v) {
        // 把所有数平方加起来
        double n = 0;
        for (float x : v) n += x * x;
        // 开根号就是这串数字的"长度"
        return Math.sqrt(n);
    }

    // ---------- 持久化: data/index.json，二次启动不重建索引 ----------

    public void save(Path file) {
        // 最外层是一个 JSON 对象，里面放一个 chunks 数组
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("chunks");
        for (Chunk c : chunks) {
            // 每条切片写成一个 JSON 对象
            ObjectNode n = arr.addObject();
            n.put("id", c.id());
            n.put("sourcePath", c.sourcePath());
            n.put("lineFrom", c.lineFrom());
            n.put("lineTo", c.lineTo());
            n.put("text", c.text());
            // 向量写成数组，一个数字一个数字放进去
            ArrayNode v = n.putArray("vector");
            for (float x : c.vector()) v.add(x);
        }
        try {
            // 把整个 JSON 写进文件
            MAPPER.writeValue(file.toFile(), root);
        } catch (IOException e) {
            // 写不进去就报错，把原因也带上
            throw new IllegalStateException("保存索引失败: " + e.getMessage(), e);
        }
    }

    public static VectorStore load(Path file) {
        // 先准备一个空库
        VectorStore store = new VectorStore();
        // 文件不存在就返回空库，让调用方自己判断
        if (!Files.exists(file)) return store;
        try {
            // 把整个文件读成 JSON
            JsonNode root = MAPPER.readTree(file.toFile());
            for (JsonNode n : root.withArray("chunks")) {
                // 先取出这条切片的向量
                JsonNode vec = n.path("vector");
                float[] v = new float[vec.size()];
                for (int i = 0; i < v.length; i++) v[i] = (float) vec.get(i).asDouble();
                // 再读出各个字段，重新拼成一条 Chunk
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
            // 读失败就报错
            throw new IllegalStateException("加载索引失败: " + e.getMessage(), e);
        }
    }
}
