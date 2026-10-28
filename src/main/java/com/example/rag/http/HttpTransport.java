package com.example.rag.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 项目唯一的网络层：所有对外 HTTP 请求都必须经过 post()。
 * 只做四件事：超时、UTF-8、重试（429/5xx/网络异常）、错误包装。
 * <p>
 * 401/400/404 等确定性错误绝不重试——同一个 key/请求体/路径重试多少次结果都一样，
 * 只会白等退避时间并掩盖真正的配置问题。重试是为了"等环境恢复"，不是"纠正错误"。
 * <p>
 * 日志走 System.err，保证 stdout 只输出业务结果（方便重定向到文件）。
 */
public final class HttpTransport {

    /** HttpClient 线程安全，全局只建一个，不要每次请求都 new */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 单个请求最多重试次数（不含首次），只对 429/5xx/网络异常生效，401/400/404 立即失败 */
    private static final int MAX_RETRIES = 3;

    /** Jackson 读写 JSON 的入口，无状态可复用；请求体序列化和响应体解析都用它 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 服务根地址，构造时已去掉结尾 /，post() 里直接与 path 拼接，避免拼出 //embeddings */
    private final String baseUrl;
    /** Bearer token，每个请求都放进 Authorization 头；为空时服务端会返回 401 */
    private final String apiKey;
    /** 为 true 时把每次成功请求的状态码与耗时打到 stderr，便于定位慢调用 */
    private final boolean debug;

    public HttpTransport(String baseUrl, String apiKey, boolean debug) {
        // 去掉结尾多余的 /，避免 baseUrl + path 拼出 //embeddings
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.debug = debug;
    }

    /** 默认 60s 请求超时（embedding 建议传 30，chat 传 60） */
    public JsonNode post(String path, ObjectNode body) {
        return post(path, body, 60);
    }

    public JsonNode post(String path, ObjectNode body, int timeoutSeconds) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);   // Jackson 直接产出 UTF-8 字节
        } catch (IOException e) {
            throw new ApiException(-1, "请求体序列化失败: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        for (int attempt = 0; ; attempt++) {
            long start = System.nanoTime();
            try {
                HttpResponse<byte[]> resp =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                long ms = (System.nanoTime() - start) / 1_000_000;
                String raw = new String(resp.body(), StandardCharsets.UTF_8);
                int status = resp.statusCode();

                if (status >= 200 && status < 300) {
                    if (debug) System.err.printf("[http] %s -> %d (%dms)%n", path, status, ms);
                    return mapper.readTree(raw);
                }
                if (retryable(status) && attempt < MAX_RETRIES) {
                    long wait = backoff(attempt, resp.headers().firstValue("Retry-After"));
                    System.err.printf("[http] %s -> %d，%dms 后第 %d/%d 次重试%n",
                            path, status, wait, attempt + 1, MAX_RETRIES);
                    sleep(wait);
                    continue;
                }
                throw new ApiException(status, raw);

            } catch (IOException e) {
                // 网络异常属于瞬时错误，可重试
                if (attempt < MAX_RETRIES) {
                    long wait = backoff(attempt, Optional.empty());
                    System.err.printf("[http] %s 网络异常(%s)，%dms 后重试%n",
                            path, e.getMessage(), wait);
                    sleep(wait);
                    continue;
                }
                throw new ApiException(-1, "网络异常(已重试 " + MAX_RETRIES + " 次): " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(-1, "请求被中断");
            }
        }
    }

    private static boolean retryable(int code) {
        return code == 429 || code >= 500;
    }

    /** 优先遵守服务端 Retry-After（单位秒），否则指数退避 500ms * 2^n + 随机抖动 */
    private static long backoff(int attempt, Optional<String> retryAfter) {
        if (retryAfter.isPresent()) {
            try {
                return Long.parseLong(retryAfter.get().trim()) * 1000L;
            } catch (NumberFormatException ignored) {
            }
        }
        long base = 500L * (1L << attempt);
        return base + ThreadLocalRandom.current().nextLong(250);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(-1, "等待重试时被中断");
        }
    }
}
