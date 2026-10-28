package com.example.rag.llm;

import com.example.rag.config.AppConfig;
import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用 /embeddings。返回的向量已做归一化，后续余弦相似度直接退化成点积。
 */
public final class EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpTransport http;
    private final String model;
    private final int batchSize;
    private final boolean debug;

    public EmbeddingClient(HttpTransport http, AppConfig config) {
        this.http = http;
        this.model = config.embedModel();
        this.batchSize = Math.max(1, config.embedBatch());
        this.debug = config.debug();
    }

    public float[] embedOne(String text) {
        return embed(List.of(text))[0];
    }

    public float[][] embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            List<String> slice = texts.subList(from, to);

            ArrayNode input = MAPPER.createArrayNode();
            slice.forEach(input::add);

            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.set("input", input);

            JsonNode response = http.post("/embeddings", body);
            JsonNode data = response.path("data");
            if (!data.isArray() || data.size() != slice.size()) {
                throw new ApiException(0, "embedding 返回条数不匹配（期望 " + slice.size()
                        + "，实际 " + data.size() + "）：" + response);
            }
            for (JsonNode item : data) {
                vectors.add(normalize(readVector(item.path("embedding"))));
            }
            if (debug) {
                System.out.printf("[embed] %d/%d 条已向量化%n", to, texts.size());
            }
        }
        return vectors.toArray(new float[0][]);
    }

    private static float[] readVector(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            throw new ApiException(0, "embedding 字段为空：" + array);
        }
        float[] vector = new float[array.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) array.get(i).asDouble();
        }
        return vector;
    }

    private static float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
