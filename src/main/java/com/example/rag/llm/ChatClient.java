package com.example.rag.llm;

import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 对话补全客户端：只做最简单的单轮 system+user 调用。
 * 打印每次耗时与 token 用量——成本可视化是手写封装的基本待遇。
 */
public class ChatClient {

    /** 网络层，指向对话服务的 chatBaseUrl；本类只调 post("/chat/completions") */
    private final HttpTransport http;
    /** 构造请求体用的 Jackson 实例 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 对话模型名，随每次请求的 model 字段上报，决定服务端选哪个模型 */
    private final String model;
    /** 本实例累计消耗的 total_tokens（多次 chat 累加），只用于打印成本，不参与业务逻辑 */
    private long totalTokens = 0;

    public ChatClient(HttpTransport http, String model) {
        this.http = http;
        this.model = model;
    }

    public long totalTokens() {
        return totalTokens;
    }

    public String chat(String systemPrompt, String userPrompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2);   // 资料问答要稳定，不要发散
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        long start = System.nanoTime();
        JsonNode resp = http.post("/chat/completions", body, 60);
        long ms = (System.nanoTime() - start) / 1_000_000;

        long tokens = resp.path("usage").path("total_tokens").asLong(0);
        totalTokens += tokens;

        String content = resp.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null) {
            throw new ApiException(-1, "chat 响应缺少 choices[0].message.content，原始响应: " + resp);
        }
        System.err.printf("[chat] %dms, %d tokens (累计 %d)%n", ms, tokens, totalTokens);
        return content;
    }
}
