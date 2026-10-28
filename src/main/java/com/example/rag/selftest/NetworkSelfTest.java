package com.example.rag.selftest;

import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络层自检：本地起一个假服务，验证 200 / 401 / 429 重试 / 404 四种情况。
 * 不需要 API Key，也不产生真实费用。
 */
public final class NetworkSelfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    private static int failures;

    public static int run() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange ->
                respond(exchange, 200, "{\"ok\":true,\"content\":\"向量检索正常\"}"));
        server.createContext("/unauthorized", exchange ->
                respond(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}"));
        server.createContext("/missing", exchange ->
                respond(exchange, 404, "{\"error\":\"not found\"}"));
        server.createContext("/retry429", exchange -> {
            if (count("/retry429") <= 2) {
                respond(exchange, 429, "{\"error\":\"rate limited\"}");
            } else {
                respond(exchange, 200, "{\"ok\":true}");
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mock-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpTransport http = new HttpTransport(baseUrl, "test-key", Duration.ofSeconds(10), 4, 20, true);

        System.out.println("=== HttpTransport 自检 ===");
        try {
            JsonNode ok = http.post("/ok", MAPPER.createObjectNode());
            check(ok.path("ok").asBoolean() && "向量检索正常".equals(ok.path("content").asText()),
                    "正常调用返回 200，中文无乱码");

            expectApiError(() -> http.post("/unauthorized", MAPPER.createObjectNode()), 401, "401 直接抛错");
            check(count("/unauthorized") == 1, "401 不重试（实际请求 1 次）");

            JsonNode retried = http.post("/retry429", MAPPER.createObjectNode());
            check(retried.path("ok").asBoolean() && peek("/retry429") == 3,
                    "429 自动重试两轮后成功（共 3 次请求）");

            expectApiError(() -> http.post("/missing", MAPPER.createObjectNode()), 404, "404 直接抛错");
            check(count("/missing") == 1, "404 不重试（实际请求 1 次）");
        } finally {
            server.stop(0);
        }

        if (failures == 0) {
            System.out.println("=== 全部通过 ===");
            return 0;
        }
        System.out.println("=== 失败 " + failures + " 项 ===");
        return 1;
    }

    private static void expectApiError(Runnable action, int expectedStatus, String label) {
        try {
            action.run();
            fail(label + "：没有抛出异常");
        } catch (ApiException e) {
            check(e.status() == expectedStatus, label + "：ApiException(" + e.status() + ")");
        }
    }

    private static void check(boolean condition, String label) {
        if (condition) {
            System.out.println("  [PASS] " + label);
        } else {
            fail(label);
        }
    }

    private static void fail(String label) {
        failures++;
        System.out.println("  [FAIL] " + label);
    }

    private static int count(String path) {
        return REQUESTS.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
    }

    /** 只读计数，不增加，避免断言本身影响结果。 */
    private static int peek(String path) {
        AtomicInteger counter = REQUESTS.get(path);
        return counter == null ? 0 : counter.get();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
