package com.example.rag.llm;

import com.example.rag.config.AppConfig;
import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 调用 /chat/completions。第一版不做流式，先保证结果正确。
 */
public final class ChatClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpTransport http;
    private final String model;
    private final boolean debug;

    public ChatClient(HttpTransport http, AppConfig config) {
        this.http = http;
        this.model = config.chatModel();
        this.debug = config.debug();
    }

    public String chat(String prompt) {
        ArrayNode messages = MAPPER.createArrayNode();
        messages.addObject()
                .put("role", "system")
                .put("content", "你是一个严谨的技术助手，只依据给定资料作答，资料里没有的内容绝不编造。");
        messages.addObject()
                .put("role", "user")
                .put("content", prompt);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("temperature", 0.2);

        JsonNode response = http.post("/chat/completions", body);
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ApiException(0, "chat 响应缺少 choices：" + response);
        }
        if (debug) {
            JsonNode usage = response.path("usage");
            System.out.printf("[chat] total_tokens=%d prompt_tokens=%d completion_tokens=%d%n",
                    usage.path("total_tokens").asInt(),
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt());
        }
        return choices.get(0).path("message").path("content").asText("").strip();
    }
}
