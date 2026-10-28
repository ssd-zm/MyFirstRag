package com.example.rag.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 全部配置集中在这里：环境变量优先，其次 config.properties，最后默认值。
 */
public record AppConfig(
        String baseUrl,
        String apiKey,
        String embedModel,
        String chatModel,
        int chunkChars,
        int overlapChars,
        int topK,
        int embedBatch,
        boolean debug) {

    public static AppConfig load() {
        Properties p = new Properties();
        Path file = Paths.get("config.properties");
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("读取 config.properties 失败", e);
            }
        }
        return new AppConfig(
                trimSlash(text(p, "RAG_BASE_URL", "baseUrl", "https://api.siliconflow.cn/v1")),
                text(p, "RAG_API_KEY", "apiKey", ""),
                text(p, "RAG_EMBED_MODEL", "embedModel", "BAAI/bge-m3"),
                text(p, "RAG_CHAT_MODEL", "chatModel", "Qwen/Qwen2.5-7B-Instruct"),
                number(p, "RAG_CHUNK_CHARS", "chunkChars", 500),
                number(p, "RAG_OVERLAP_CHARS", "overlapChars", 80),
                number(p, "RAG_TOP_K", "topK", 4),
                number(p, "RAG_EMBED_BATCH", "embedBatch", 16),
                Boolean.parseBoolean(text(p, "RAG_DEBUG", "debug", "false")));
    }

    public void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("""
                    缺少 API Key。任选一种：
                      1) 复制 config.properties.example 为 config.properties，填写 apiKey
                      2) 设置环境变量 RAG_API_KEY=sk-xxx
                    """);
        }
    }

    private static String text(Properties p, String envKey, String fileKey, String defaultValue) {
        String value = System.getenv(envKey);
        if (value == null || value.isBlank()) {
            value = p.getProperty(fileKey);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int number(Properties p, String envKey, String fileKey, int defaultValue) {
        String value = text(p, envKey, fileKey, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + fileKey + " 不是合法整数：" + value);
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
