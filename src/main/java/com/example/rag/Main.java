package com.example.rag;

import com.example.rag.config.AppConfig;
import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.example.rag.ingest.FileText;
import com.example.rag.ingest.MarkdownLoader;
import com.example.rag.ingest.TextSplitter;
import com.example.rag.llm.ChatClient;
import com.example.rag.llm.EmbeddingClient;
import com.example.rag.rag.PromptBuilder;
import com.example.rag.rag.Retriever;
import com.example.rag.store.Chunk;
import com.example.rag.store.Scored;
import com.example.rag.store.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 入口。只做流程控制，不写业务逻辑。
 * <p>
 * 用法:
 *   java -jar rag-demo.jar selftest        网络层自检（配 key 后先跑这个）
 *   java -jar rag-demo.jar index           建索引（读取 notes/ 下所有 md/txt）
 *   java -jar rag-demo.jar ask "问题"      基于笔记问答
 */
public class Main {

    public static void main(String[] args) {
        // Windows 控制台默认 GBK，强制 stdout 用 UTF-8 输出中文
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        //初始化args数据
        args = new String[]{"selftest", "你好"};
//        args = new String[]{"ask", "你好"};

        if (args.length < 1) {
            System.out.println("""
                    用法:
                      java -jar rag-demo.jar selftest          网络层自检（配 key 后先跑这个）
                      java -jar rag-demo.jar index             建索引（读取 notes/ 下所有 md/txt）
                      java -jar rag-demo.jar embed-check "问题" 只看检索命中，不调 LLM（调试用）
                      java -jar rag-demo.jar ask "问题"        基于笔记问答""");
            System.exit(2);
        }
        try {
            switch (args[0]) {
                case "selftest" -> selftest();
                case "index" -> index();
                case "ask" -> {
                    if (args.length < 2) {
                        System.out.println("缺少问题: ask \"你的问题\"");
                        System.exit(2);
                    }
                    ask(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                case "embed-check" -> {
                    if (args.length < 2) {
                        System.out.println("缺少问题: embed-check \"你的问题\"");
                        System.exit(2);
                    }
                    embedCheck(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                default -> {
                    System.out.println("未知命令: " + args[0]);
                    System.exit(2);
                }
            }
        } catch (IllegalStateException | ApiException e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---------- index: 建库 ----------

    private static void index() {
        AppConfig cfg = AppConfig.load();
        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());

        List<FileText> files = new MarkdownLoader().loadAll(cfg.notesDir());
        System.out.println("读取文件: " + files.size() + " 个");

        List<Chunk> chunks = new ArrayList<>();
        TextSplitter splitter = new TextSplitter();
        for (FileText f : files) {
            chunks.addAll(splitter.split(f, cfg.chunkChars(), cfg.overlapChars()));
        }
        System.out.println("切出 chunk: " + chunks.size() + " 条");
        if (chunks.isEmpty()) {
            System.out.println("没有可索引的内容，请先往 notes/ 放 .md 文件");
            return;
        }

        System.out.println("向量化中...");
        List<float[]> vectors = embedder.embedBatch(chunks.stream().map(Chunk::text).toList());
        System.out.println("向量维度: " + vectors.get(0).length
                + ", embedding tokens: " + embedder.totalTokens());

        List<Chunk> indexed = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            indexed.add(new Chunk(c.id(), c.sourcePath(), c.lineFrom(), c.lineTo(), c.text(), vectors.get(i)));
        }
        VectorStore store = new VectorStore();
        store.addAll(indexed);

        try {
            Files.createDirectories(cfg.dataDir());
            Path idx = cfg.dataDir().resolve("index.json");
            store.save(idx);
            System.out.println("索引已保存: " + idx.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("保存索引失败: " + e.getMessage(), e);
        }
    }

    // ---------- embed-check: 只验证检索，不调 LLM ----------

    private static void embedCheck(String question) {
        AppConfig cfg = AppConfig.load();
        Path idx = cfg.dataDir().resolve("index.json");
        if (!Files.exists(idx)) {
            System.out.println("索引不存在，先运行: index");
            System.exit(1);
        }
        VectorStore store = VectorStore.load(idx);
        if (store.size() == 0) {
            System.out.println("索引为空，先运行: index");
            System.exit(1);
        }
        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());

        List<Scored> hits = new Retriever(embedder, store).retrieve(question, cfg.topK());
        System.out.println("检索命中 (共 " + store.size() + " 条索引, topK=" + cfg.topK()
                + ", embedding tokens=" + embedder.totalTokens() + "):");
        if (hits.isEmpty()) {
            System.out.println("  没有命中任何内容");
            return;
        }
        for (Scored s : hits) {
            String name = Path.of(s.chunk().sourcePath()).getFileName().toString();
            System.out.printf("  %.4f  %s:%d-%d行%n",
                    s.score(), name, s.chunk().lineFrom(), s.chunk().lineTo());
            String preview = s.chunk().text().replace("\n", " ");
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
            System.out.println("        " + preview);
        }
        System.out.println("提示: 分数普遍 <0.4 说明没检到，调 chunkChars/topK；检索对了但答案不对，才去调 prompt");
    }

    // ---------- ask: 问答 ----------

    private static void ask(String question) {
        AppConfig cfg = AppConfig.load();
        Path idx = cfg.dataDir().resolve("index.json");
        if (!Files.exists(idx)) {
            System.out.println("索引不存在，先运行: index");
            System.exit(1);
        }
        VectorStore store = VectorStore.load(idx);
        if (store.size() == 0) {
            System.out.println("索引为空，先运行: index");
            System.exit(1);
        }

        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());
        ChatClient chat = new ChatClient(
                new HttpTransport(cfg.chatBaseUrl(), cfg.chatApiKey(), cfg.debug()),
                cfg.chatModel());

        List<Scored> hits = new Retriever(embedder, store).retrieve(question, cfg.topK());
        if (hits.isEmpty()) {
            System.out.println("没有检索到任何内容");
            return;
        }
        if (cfg.debug()) {
            for (Scored s : hits) {
                System.err.printf("[debug] %.4f %s%n", s.score(), s.chunk().id());
            }
        }

        String[] p = PromptBuilder.build(hits, question);
        String answer = chat.chat(p[0], p[1]);

        System.out.println("答案:");
        System.out.println(answer);
        System.out.println("\n命中来源:");
        for (Scored s : hits) {
            String name = Path.of(s.chunk().sourcePath()).getFileName().toString();
            System.out.printf("  %.4f  %s:%d行%n", s.score(), name, s.chunk().lineFrom());
        }
        System.out.println("本次 tokens: embedding=" + embedder.totalTokens()
                + ", chat=" + chat.totalTokens());
    }

    // ---------- selftest: 网络层自检 ----------

    private static void selftest() {
        ObjectMapper mapper = new ObjectMapper();
        AppConfig cfg = AppConfig.load();
        int pass = 0, fail = 0;

        // 1. chat 正常调用
        try {
            ChatClient chat = new ChatClient(
                    new HttpTransport(cfg.chatBaseUrl(), cfg.chatApiKey(), cfg.debug()),
                    cfg.chatModel());
            String r = chat.chat("你是回声机", "只回复两个字: 正常").strip();
            System.out.println("PASS [chat] 回复: " + r);
            pass++;
        } catch (Exception e) {
            System.out.println("FAIL [chat] " + e.getMessage());
            fail++;
        }

        // 2. embedding 正常调用
        try {
            EmbeddingClient emb = new EmbeddingClient(
                    new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                    cfg.embedModel(), cfg.embedBatch());
            float[] v = emb.embedOne("向量维度自检");
            System.out.printf("PASS [embed] 维度=%d 前3维=%.4f/%.4f/%.4f tokens=%d%n",
                    v.length, v[0], v[1], v[2], emb.totalTokens());
            pass++;
        } catch (Exception e) {
            System.out.println("FAIL [embed] " + e.getMessage());
            fail++;
        }

        // 3. 假 key: 必须立刻失败(401/400)且不触发重试
        try {
            HttpTransport bad = new HttpTransport(cfg.chatBaseUrl(), "sk-invalid-key", false);
            ObjectNode body = mapper.createObjectNode().put("model", cfg.chatModel());
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            bad.post("/chat/completions", body, 15);
            System.out.println("FAIL [401] 假 key 竟然成功了，检查 baseUrl 是否指向无需认证的服务");
            fail++;
        } catch (ApiException e) {
            if (e.statusCode == 401 || e.statusCode == 400) {
                System.out.println("PASS [401] 假 key 被立刻拒绝, 状态码=" + e.statusCode);
                pass++;
            } else {
                System.out.println("FAIL [401] 期望 401/400, 实际 " + e.statusCode);
                fail++;
            }
        }

        // 4. 不存在的路径: 404, 同样不重试
        try {
            HttpTransport good = new HttpTransport(cfg.chatBaseUrl(), cfg.chatApiKey(), false);
            good.post("/nonexistent-path", mapper.createObjectNode(), 15);
            System.out.println("FAIL [404] 不存在的路径竟然成功了");
            fail++;
        } catch (ApiException e) {
            if (e.statusCode == 404) {
                System.out.println("PASS [404] 正确返回 404");
                pass++;
            } else {
                System.out.println("FAIL [404] 期望 404, 实际 " + e.statusCode);
                fail++;
            }
        }

        System.out.printf("%n自检结果: %d 通过, %d 失败%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
