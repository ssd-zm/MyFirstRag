package com.example.rag.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 唯一的网络出口：超时、重试、退避、错误透出、耗时日志都在这里。
 * 上层（Embedding / Chat）只关心 path + 请求体，不碰 HttpClient。
 */
public final class HttpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HttpClient 线程安全，全局复用一个实例即可。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final boolean debug;

    public HttpTransport(String baseUrl, String apiKey, Duration timeout,
                         int maxAttempts, long baseBackoffMs, boolean debug) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(1, baseBackoffMs);
        this.debug = debug;
    }

    public JsonNode post(String path, ObjectNode body) {
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new UncheckedIOException("请求体序列化失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        for (int attempt = 0; ; attempt++) {
            long startedAt = System.nanoTime();
            HttpResponse<byte[]> response;
            try {
                response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt + 1 >= maxAttempts) {
                    throw new ApiException(-1, "网络异常：" + e);
                }
                long waitMs = backoff(attempt, Optional.empty());
                log("网络异常(%s)，%dms 后重试第 %d 次", e.getClass().getSimpleName(), waitMs, attempt + 1);
                sleep(waitMs);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(-2, "请求被中断");
            }

            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            int status = response.statusCode();
            String raw = new String(response.body(), StandardCharsets.UTF_8);

            if (status >= 200 && status < 300) {
                log("POST %s -> %d (%dms)", path, status, costMs);
                try {
                    return MAPPER.readTree(raw);
                } catch (IOException e) {
                    throw new ApiException(status, "响应不是合法 JSON：" + raw);
                }
            }

            // 400 / 401 / 404 这类重试没有意义，直接抛出原始错误
            if (!retryable(status) || attempt + 1 >= maxAttempts) {
                throw new ApiException(status, raw);
            }

            long waitMs = backoff(attempt, response.headers().firstValue("Retry-After"));
            log("POST %s -> %d，%dms 后重试第 %d 次", path, status, waitMs, attempt + 1);
            sleep(waitMs);
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private long backoff(int attempt, Optional<String> retryAfter) {
        if (retryAfter.isPresent()) {
            try {
                long ms = Long.parseLong(retryAfter.get().trim()) * 1000L;
                return Math.min(ms, 10_000L);
            } catch (NumberFormatException ignored) {
                // 有些服务返回的是日期格式，忽略，走指数退避
            }
        }
        long base = baseBackoffMs * (1L << Math.min(attempt, 6));
        return base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(-2, "请求被中断");
        }
    }

    private void log(String format, Object... args) {
        if (debug) {
            System.out.println("[http] " + String.format(format, args));
        }
    }
}
