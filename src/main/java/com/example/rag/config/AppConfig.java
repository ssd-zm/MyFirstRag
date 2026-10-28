package com.example.rag.config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 不可变配置。优先级：环境变量 RAG_* > config.properties > 内置默认值。
 * <p>
 * 默认组合：chat=DeepSeek（官方无 embedding 接口），embedding=硅基流动 bge-m3。
 * 最少只需要填两个 key。想一家全包可改用阿里百炼（见 config.properties.example 注释）。
 */
public record AppConfig(
        String embedBaseUrl, String embedApiKey, String embedModel,
        String chatBaseUrl, String chatApiKey, String chatModel,
        Path notesDir, Path dataDir,
        int chunkChars, int overlapChars, int topK, int embedBatch,
        boolean debug) {

    public static AppConfig load() {
        Properties p = new Properties();
        Path file = Path.of("config.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                // Properties.load(InputStream) 默认 ISO-8859-1，这里显式用 UTF-8
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("读取 config.properties 失败: " + e.getMessage(), e);
            }
        }

        AppConfig c = new AppConfig(
                get(p, "embedBaseUrl", "RAG_EMBED_BASE_URL", "https://api.siliconflow.cn/v1"),
                get(p, "embedApiKey", "RAG_EMBED_API_KEY", ""),
                get(p, "embedModel", "RAG_EMBED_MODEL", "BAAI/bge-m3"),
                get(p, "chatBaseUrl", "RAG_CHAT_BASE_URL", "https://api.deepseek.com"),
                get(p, "chatApiKey", "RAG_CHAT_API_KEY", ""),
                get(p, "chatModel", "RAG_CHAT_MODEL", "deepseek-chat"),
                Path.of(get(p, "notesDir", "RAG_NOTES_DIR", "notes")),
                Path.of(get(p, "dataDir", "RAG_DATA_DIR", "data")),
                getInt(p, "chunkChars", 500),
                getInt(p, "overlapChars", 80),
                getInt(p, "topK", 4),
                getInt(p, "embedBatch", 16),
                Boolean.parseBoolean(get(p, "debug", "RAG_DEBUG", "false")));

        c.validate();
        return c;
    }

    private void validate() {
        if (embedApiKey.isBlank() || chatApiKey.isBlank()) {
            throw new IllegalStateException("""
                    缺少 API key。两种配法任选:
                    1) 复制 config.properties.example 为 config.properties，填入 chatApiKey 和 embedApiKey
                    2) 设置环境变量 RAG_CHAT_API_KEY 和 RAG_EMBED_API_KEY""");
        }
        if (chunkChars < 100 || overlapChars < 0 || overlapChars >= chunkChars) {
            throw new IllegalStateException(
                    "参数不合法: 要求 chunkChars >= 100 且 0 <= overlapChars < chunkChars");
        }
    }

    private static String get(Properties p, String key, String env, String def) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank()) return v.trim();
        v = p.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int getInt(Properties p, String key, int def) {
        String raw = p.getProperty(key, String.valueOf(def)).trim();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置项 " + key + " 不是整数: " + raw);
        }
    }
}
