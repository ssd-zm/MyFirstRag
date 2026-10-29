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

    // 网络层，真正发 HTTP 请求用的，这里只调对话接口
    private final HttpTransport http;
    // 生成、解析 JSON 用的工具
    private final ObjectMapper mapper = new ObjectMapper();
    // 模型名字，发给服务端，服务端据此选模型
    private final String model;
    // 这个对象一共花掉了多少 token，用来估算成本
    private long totalTokens = 0;

    public ChatClient(HttpTransport http, String model) {
        // 把传进来的值存到字段里
        this.http = http;
        this.model = model;
    }

    public long totalTokens() {
        // 外部想知道花了多少 token 时调用
        return totalTokens;
    }

    public String chat(String systemPrompt, String userPrompt) {
        // 开始组装请求体，先放模型名
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // 温度调低一点，资料问答要稳定，不要自由发挥
        body.put("temperature", 0.2);
        // messages 是一个数组，把一条条对话放进去
        ArrayNode messages = body.putArray("messages");
        // 第一条是系统设定，告诉模型它该怎么回答
        messages.addObject().put("role", "system").put("content", systemPrompt);
        // 第二条是用户的问题（以及拼进去的资料）
        messages.addObject().put("role", "user").put("content", userPrompt);

        // 记下开始时间，用来算请求花了多久
        long start = System.nanoTime();
        // 真正发请求，最多等 60 秒
        JsonNode resp = http.post("/chat/completions", body, 60);
        long ms = (System.nanoTime() - start) / 1_000_000;

        // 从响应里取总 token 数，没有就当 0
        long tokens = resp.path("usage").path("total_tokens").asLong(0);
        // 累加到总数里
        totalTokens += tokens;

        // 答案在 choices[0].message.content，一层层取出来
        String content = resp.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null) {
            // 结构不对就报错，把原始响应带上方便排查
            throw new ApiException(-1, "chat 响应缺少 choices[0].message.content，原始响应: " + resp);
        }
        // 把耗时和 token 打到日志里，方便看到成本
        System.err.printf("[chat] %dms, %d tokens (累计 %d)%n", ms, tokens, totalTokens);
        return content;
    }
}
