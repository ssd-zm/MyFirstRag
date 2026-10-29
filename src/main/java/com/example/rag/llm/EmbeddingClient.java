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

    // 网络层，真正发 HTTP 请求用的，这里只调向量化接口
    private final HttpTransport http;
    // 生成、解析 JSON 用的工具
    private final ObjectMapper mapper = new ObjectMapper();
    // 模型名字，不同模型算出来的向量长度可能不一样
    private final String model;
    // 一次请求最多带几条文本，太多了容易被服务端拒绝
    private final int batch;
    // 一共花掉了多少 token，用来估算建索引的成本
    private long totalTokens = 0;

    public EmbeddingClient(HttpTransport http, String model, int batch) {
        // 把传进来的值存到字段里
        this.http = http;
        this.model = model;
        // batch 至少是 1，不然下面的循环会卡住
        this.batch = Math.max(1, batch);
    }

    public long totalTokens() {
        // 外部想知道花了多少 token 时调用
        return totalTokens;
    }

    /** 批量向量化，返回顺序与输入一致，所有向量维度相同 */
    public List<float[]> embedBatch(List<String> texts) {
        // 最终结果，顺序和传进来的 texts 一样
        List<float[]> all = new ArrayList<>(texts.size());
        // 一次处理 batch 条，from 是这一批的起点
        for (int from = 0; from < texts.size(); from += batch) {
            // 切出这一批，注意最后一批可能凑不满
            List<String> sub = texts.subList(from, Math.min(from + batch, texts.size()));
            // 发一次请求，把拿到的向量追加到结果里
            all.addAll(embedOneBatch(sub));
        }
        if (!all.isEmpty()) {
            // 用第一条的长度当标准，检查其它条是不是一样长
            int dim = all.get(0).length;
            for (float[] v : all) {
                if (v.length != dim) {
                    // 长度不一样说明服务端返回有问题，直接报错
                    throw new IllegalStateException("向量维度不一致: " + dim + " vs " + v.length);
                }
            }
        }
        return all;
    }

    public float[] embedOne(String text) {
        // 只有一条时也走批量方法，再把唯一的结果取出来
        return embedBatch(List.of(text)).get(0);
    }

    private List<float[]> embedOneBatch(List<String> texts) {
        // 组装请求体，先放模型名
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // input 是数组，里面放要向量化的文本
        ArrayNode input = body.putArray("input");
        for (String t : texts) {
            // 个别供应商拒绝空串，兜底成单个空格
            input.add(t == null || t.isBlank() ? " " : t);
        }
        // 发请求，向量化一般比较快，最多等 30 秒
        JsonNode resp = http.post("/embeddings", body, 30);

        // 顺手把这次的 token 用量累加起来
        JsonNode usage = resp.get("usage");
        if (usage != null && usage.has("total_tokens")) {
            totalTokens += usage.get("total_tokens").asLong(0);
        }

        // data 是结果数组，每条对应一条输入
        JsonNode data = resp.get("data");
        // 先看条数对不对，对不上说明模型名或请求有问题
        int size = data == null || !data.isArray() ? -1 : data.size();
        if (size != texts.size()) {
            throw new ApiException(-1,
                    "embeddings 响应异常: data.size()=" + (size < 0 ? "null" : size)
                            + "，期望 " + texts.size() + "。检查模型名是否正确");
        }

        // 按 index 归位，不依赖服务端返回顺序
        float[][] out = new float[texts.size()][];
        for (JsonNode item : data) {
            // 每条结果都有个 index，表示它对应第几条输入
            int idx = item.path("index").asInt(-1);
            // embedding 就是那串表示语义的数字
            JsonNode emb = item.get("embedding");
            if (idx < 0 || idx >= out.length || emb == null || !emb.isArray()) {
                // 字段缺失或越界，说明返回格式不对
                throw new ApiException(-1, "embeddings 响应缺少合法的 index/embedding 字段");
            }
            // 把 JSON 里的数字数组转成 Java 的 float 数组
            float[] v = new float[emb.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            // 按位置放回去
            out[idx] = v;
        }

        // 按顺序收集成 List 返回
        List<float[]> list = new ArrayList<>(out.length);
        for (float[] v : out) {
            // 有一个位置是空的，说明少了一条结果
            if (v == null) throw new ApiException(-1, "embeddings 响应缺少部分 index");
            list.add(v);
        }
        return list;
    }
}
