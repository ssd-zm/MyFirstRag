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
        // 向量化服务的地址，后面会再拼 /embeddings，比如 https://api.siliconflow.cn/v1
        String embedBaseUrl,
        // 向量化服务的 key，不能为空，不然启动就报错
        String embedApiKey,
        // 向量化模型的名字，默认 BAAI/bge-m3，写错服务端一般返回 400
        String embedModel,
        // 对话服务的地址，后面会再拼 /chat/completions，默认 DeepSeek
        String chatBaseUrl,
        // 对话服务的 key，也不能为空，可以和上面的 key 来自不同公司
        String chatApiKey,
        // 对话模型的名字，默认 deepseek-chat
        String chatModel,
        // 笔记目录，建索引时从这里读 .md/.txt 文件
        Path notesDir,
        // 索引存放目录，index.json 就存在这里
        Path dataDir,
        // 每个切片最多大概多少个字，默认 500，调大切片就更粗
        int chunkChars,
        // 相邻切片重叠多少个字，默认 80，让切开的句子还能接上
        int overlapChars,
        // 每次提问取回几条资料，默认 4，取太多会占满模型能看的长度
        int topK,
        // 一次请求带几条文本去算向量，默认 16，太大容易被服务端拒绝
        int embedBatch,
        // 是否打印调试日志，默认不打
        boolean debug) {

    public static AppConfig load() {
        // 用一个 Properties 装配置文件里的内容
        Properties p = new Properties();
        // 配置文件放在项目根目录，名字固定
        Path file = Path.of("config.properties");
        if (Files.exists(file)) {
            // try-with-resources：读完自动关闭文件
            try (InputStream in = Files.newInputStream(file)) {
                // Properties.load(InputStream) 默认 ISO-8859-1，这里显式用 UTF-8
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                // 配置文件读不了，直接报错
                throw new IllegalStateException("读取 config.properties 失败: " + e.getMessage(), e);
            }
        }

        // 依次按"配置项名、环境变量名、默认值"取值，拼成一个配置对象
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

        // 检查必填项和参数是否合理，有问题会抛异常
        c.validate();
        return c;
    }

    private void validate() {
        // 两个 key 都是必填，缺一个都启动不了
        if (embedApiKey.isBlank() || chatApiKey.isBlank()) {
            throw new IllegalStateException("""
                    缺少 API key。两种配法任选:
                    1) 复制 config.properties.example 为 config.properties，填入 chatApiKey 和 embedApiKey
                    2) 设置环境变量 RAG_CHAT_API_KEY 和 RAG_EMBED_API_KEY""");
        }
        // 切片长度和重叠长度也得合理，否则切分时会出问题
        if (chunkChars < 100 || overlapChars < 0 || overlapChars >= chunkChars) {
            throw new IllegalStateException(
                    "参数不合法: 要求 chunkChars >= 100 且 0 <= overlapChars < chunkChars");
        }
    }

    private static String get(Properties p, String key, String env, String def) {
        // 先看环境变量有没有配置
        String v = System.getenv(env);
        // 环境变量有值就用它，去掉首尾空白
        if (v != null && !v.isBlank()) return v.trim();
        // 环境变量没有，再看配置文件
        v = p.getProperty(key);
        // 都没有就用默认值
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int getInt(Properties p, String key, int def) {
        // 先取出字符串，没配就用默认值
        String raw = p.getProperty(key, String.valueOf(def)).trim();
        try {
            // 把字符串转成整数
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            // 转不了说明配置写错了，报错时点明是哪个配置项
            throw new IllegalStateException("配置项 " + key + " 不是整数: " + raw);
        }
    }
}
