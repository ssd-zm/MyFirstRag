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

    // 发 HTTP 请求的工具，它是线程安全的，整个程序只建一个，不用每次请求都新建
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            // 连不上服务端时最多等 10 秒
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 一个请求失败后最多再试几次（不算第一次），只对 429/5xx/网络异常有效
    private static final int MAX_RETRIES = 3;

    // 读写 JSON 的工具，本身没有状态，可以反复用
    private final ObjectMapper mapper = new ObjectMapper();
    // 服务地址，比如 https://api.deepseek.com，构造时已经去掉了结尾的 /
    private final String baseUrl;
    // 身份凭证 key，每次请求都要放在请求头里
    private final String apiKey;
    // 为 true 时把每次请求的耗时打到日志里，方便看哪里慢
    private final boolean debug;

    public HttpTransport(String baseUrl, String apiKey, boolean debug) {
        // 去掉结尾多余的 /，避免 baseUrl + path 拼出 //embeddings
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 把传进来的 key 和日志开关存到字段里
        this.apiKey = apiKey;
        this.debug = debug;
    }

    /** 默认 60s 请求超时（embedding 建议传 30，chat 传 60） */
    public JsonNode post(String path, ObjectNode body) {
        // 没指定超时就按 60 秒算，交给下面那个方法真正干活
        return post(path, body, 60);
    }

    public JsonNode post(String path, ObjectNode body, int timeoutSeconds) {
        // HTTP 只能发字节，所以先把请求对象转成 JSON 字节
        byte[] payload;
        try {
            // Jackson 直接产出 UTF-8 字节
            payload = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            // 状态码写 -1，表示连请求都没发出去
            throw new ApiException(-1, "请求体序列化失败: " + e.getMessage());
        }

        // 组装这次要发的请求
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                // 这个地址最多等多久
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // 服务端靠这个头认出我们是谁
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                // 用 POST 方法把字节发出去
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        // attempt 是第几次尝试，从 0 开始，一直循环到有结果为止
        for (int attempt = 0; ; attempt++) {
            // 记下开始时间，后面算这次请求花了多久
            long start = System.nanoTime();
            try {
                // 真正把请求发出去，并等响应回来
                HttpResponse<byte[]> resp =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                // 纳秒换算成毫秒
                long ms = (System.nanoTime() - start) / 1_000_000;
                // 响应体是字节，这里按 UTF-8 转成文字
                String raw = new String(resp.body(), StandardCharsets.UTF_8);
                // 取出状态码，200 多表示成功
                int status = resp.statusCode();

                if (status >= 200 && status < 300) {
                    if (debug) System.err.printf("[http] %s -> %d (%dms)%n", path, status, ms);
                    // 成功就把文字解析成 JSON 返回
                    return mapper.readTree(raw);
                }
                // 429 表示请求太频繁，5xx 表示服务端自己出错，这两种可以等等再试
                if (retryable(status) && attempt < MAX_RETRIES) {
                    // 算出这次要等多久
                    long wait = backoff(attempt, resp.headers().firstValue("Retry-After"));
                    System.err.printf("[http] %s -> %d，%dms 后第 %d/%d 次重试%n",
                            path, status, wait, attempt + 1, MAX_RETRIES);
                    // 睡一会儿再进入下一轮
                    sleep(wait);
                    continue;
                }
                // 其它错误（比如 401、404）重试也没用，直接把状态码和原文抛出去
                throw new ApiException(status, raw);

            } catch (IOException e) {
                // 网络异常属于瞬时错误，可重试
                if (attempt < MAX_RETRIES) {
                    long wait = backoff(attempt, Optional.empty());
                    System.err.printf("[http] %s 网络异常(%s)，%dms 后重试%n",
                            path, e.getMessage(), wait);
                    sleep(wait);
                    // continue 回到循环开头，再发一次
                    continue;
                }
                // 试满次数还是不行，包成自己的异常抛出
                throw new ApiException(-1, "网络异常(已重试 " + MAX_RETRIES + " 次): " + e.getMessage());
            } catch (InterruptedException e) {
                // 线程被要求中断，把中断标记还回去
                Thread.currentThread().interrupt();
                throw new ApiException(-1, "请求被中断");
            }
        }
    }

    private static boolean retryable(int code) {
        // 429 和 500 以上才值得重试，其它错误重试结果也一样
        return code == 429 || code >= 500;
    }

    /** 优先遵守服务端 Retry-After（单位秒），否则指数退避 500ms * 2^n + 随机抖动 */
    private static long backoff(int attempt, Optional<String> retryAfter) {
        // 服务端如果说了等几秒，就听它的
        if (retryAfter.isPresent()) {
            try {
                // 响应头里写的是秒，这里换成毫秒
                return Long.parseLong(retryAfter.get().trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // 解析不了就不管，走下面的默认算法
            }
        }
        // 默认算法：第一次等 500ms，之后每次翻倍
        long base = 500L * (1L << attempt);
        // 再加一点随机时间，避免多个请求同时重试
        return base + ThreadLocalRandom.current().nextLong(250);
    }

    private static void sleep(long ms) {
        try {
            // 让当前线程睡一会儿
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 睡觉时被打断，先恢复中断标记
            Thread.currentThread().interrupt();
            throw new ApiException(-1, "等待重试时被中断");
        }
    }
}
