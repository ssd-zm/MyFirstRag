package com.example.rag.llm;

import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化客户端。与 ChatClient 完全解耦，可独立更换 embedding 供应商。
 * <p>
 * 关键点：
 * - 按 batch 分批调用，避免超出单次请求的条数上限
 * - 响应中每条向量带 index，必须按 index 归位，保证返回顺序与输入一致
 * - 累计 usage.total_tokens，建索引的花费心里有数
 */
public class EmbeddingClient {

    private final HttpTransport http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String model;
    private final int batch;
    private long totalTokens = 0;

    public EmbeddingClient(HttpTransport http, String model, int batch) {
        this.http = http;
        this.model = model;
        this.batch = Math.max(1, batch);
    }

    public long totalTokens() {
        return totalTokens;
    }

    /** 批量向量化，返回顺序与输入一致，所有向量维度相同 */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batch) {
            List<String> sub = texts.subList(from, Math.min(from + batch, texts.size()));
            all.addAll(embedOneBatch(sub));
        }
        if (!all.isEmpty()) {
            int dim = all.get(0).length;
            for (float[] v : all) {
                if (v.length != dim) {
                    throw new IllegalStateException("向量维度不一致: " + dim + " vs " + v.length);
                }
            }
        }
        return all;
    }

    public float[] embedOne(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    private List<float[]> embedOneBatch(List<String> texts) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String t : texts) {
            // 个别供应商拒绝空串，兜底成单个空格
            input.add(t == null || t.isBlank() ? " " : t);
        }
        JsonNode resp = http.post("/embeddings", body, 30);

        JsonNode usage = resp.get("usage");
        if (usage != null && usage.has("total_tokens")) {
            totalTokens += usage.get("total_tokens").asLong(0);
        }

        JsonNode data = resp.get("data");
        int size = data == null || !data.isArray() ? -1 : data.size();
        if (size != texts.size()) {
            throw new ApiException(-1,
                    "embeddings 响应异常: data.size()=" + (size < 0 ? "null" : size)
                            + "，期望 " + texts.size() + "。检查模型名是否正确");
        }

        // 按 index 归位，不依赖服务端返回顺序
        float[][] out = new float[texts.size()][];
        for (JsonNode item : data) {
            int idx = item.path("index").asInt(-1);
            JsonNode emb = item.get("embedding");
            if (idx < 0 || idx >= out.length || emb == null || !emb.isArray()) {
                throw new ApiException(-1, "embeddings 响应缺少合法的 index/embedding 字段");
            }
            float[] v = new float[emb.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            out[idx] = v;
        }

        List<float[]> list = new ArrayList<>(out.length);
        for (float[] v : out) {
            if (v == null) throw new ApiException(-1, "embeddings 响应缺少部分 index");
            list.add(v);
        }
        return list;
    }
}
