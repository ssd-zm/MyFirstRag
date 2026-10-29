package com.example.rag;

import com.example.rag.config.AppConfig;
import com.example.rag.eval.EvalRunner;
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
        // stderr 也要一起设，否则 [debug]/[chat] 这类打到错误流的日志会乱码
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        //初始化args数据
//        args = new String[]{"selftest", "你好"};
//        args = new String[]{"index", "你好"};
//        args = new String[]{"embed-check", "为什么要切片"};
//        args = new String[]{"ask", "模型的上下文窗口有限，整篇文档塞进去既贵又容易被无关内容干扰。切片的目标是让每一块都能独立表达一个完整的意思，检索时命中的块直接可用。"};
//        args = new String[]{"ask", "你好，请问为什么要切片？"};
        args = new String[]{"eval"};
        // 没有任何命令行参数时（比如 IDE 里直接点运行）走这行调试；命令行传了参数就按参数执行
        if (args.length == 0) {
            args = new String[]{"ask", "你好，测试一下为什么要切片"};
        }
        System.out.println("输入:"+ Arrays.toString(args));

        // 一个命令都没给，就打印用法并退出
        if (args.length < 1) {
            System.out.println("""
                    用法:
                      java -jar rag-demo.jar selftest          网络层自检（配 key 后先跑这个）
                      java -jar rag-demo.jar index             建索引（读取 notes/ 下所有 md/txt）
                      java -jar rag-demo.jar embed-check "问题" 只看检索命中，不调 LLM（调试用）
                      java -jar rag-demo.jar eval [tsv]        跑测评集，看 Recall@K 和分数分布
                      java -jar rag-demo.jar ask "问题"        基于笔记问答""");
            System.exit(2);
        }
        try {
            // 第一个参数决定要做什么
            switch (args[0]) {
                case "selftest" -> selftest();
                case "index" -> index();
                // eval 可以带测评集路径，不带就用默认的 eval/questions.tsv
                case "eval" -> eval(args.length > 1 ? args[1] : "eval/questions.tsv");
                case "ask" -> {
                    // ask 后面必须带问题
                    if (args.length < 2) {
                        System.out.println("缺少问题: ask \"你的问题\"");
                        System.exit(2);
                    }
                    // 把被空格分开的几段问题重新拼成一句
                    ask(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                case "embed-check" -> {
                    // embed-check 后面也必须带问题
                    if (args.length < 2) {
                        System.out.println("缺少问题: embed-check \"你的问题\"");
                        System.exit(2);
                    }
                    embedCheck(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                default -> {
                    // 不认识的命令
                    System.out.println("未知命令: " + args[0]);
                    System.exit(2);
                }
            }
        } catch (IllegalStateException | ApiException e) {
            // 出错就打印原因，并以失败状态退出
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---------- index: 建库 ----------

    private static void index() {
        // 先读配置
        AppConfig cfg = AppConfig.load();
        // 准备向量化客户端：给它网络层、模型名、每批条数
        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());

        // 从笔记目录读出所有文本文件
        List<FileText> files = new MarkdownLoader().loadAll(cfg.notesDir());
        System.out.println("读取文件: " + files.size() + " 个");

        // 把所有文件切成一片一片（chunk）
        List<Chunk> chunks = new ArrayList<>();
        TextSplitter splitter = new TextSplitter();
        for (FileText f : files) {
            // 每个文件按配置的长度和重叠切
            chunks.addAll(splitter.split(f, cfg.chunkChars(), cfg.overlapChars()));
        }
        System.out.println("切出 chunk: " + chunks.size() + " 条");
        if (chunks.isEmpty()) {
            // 一条都没有，说明目录里还没东西，直接结束
            System.out.println("没有可索引的内容，请先往 notes/ 放 .md 文件");
            return;
        }

        System.out.println("向量化中...");
        // 把所有切片的文字批量送去算向量
        List<float[]> vectors = embedder.embedBatch(chunks.stream().map(Chunk::text).toList());
        System.out.println("向量维度: " + vectors.get(0).length
                + ", embedding tokens: " + embedder.totalTokens());

        // 把向量一条条填回对应切片，得到最终要存起来的索引
        List<Chunk> indexed = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            indexed.add(new Chunk(c.id(), c.sourcePath(), c.lineFrom(), c.lineTo(), c.text(), vectors.get(i)));
        }
        VectorStore store = new VectorStore();
        store.addAll(indexed);

        try {
            // 目录不存在就先建出来
            Files.createDirectories(cfg.dataDir());
            // 索引固定存成 data/index.json
            Path idx = cfg.dataDir().resolve("index.json");
            store.save(idx);
            System.out.println("索引已保存: " + idx.toAbsolutePath());
        } catch (Exception e) {
            // 保存失败就报错，带上原因
            throw new IllegalStateException("保存索引失败: " + e.getMessage(), e);
        }
    }

    // ---------- embed-check: 只验证检索，不调 LLM ----------

    private static void embedCheck(String question) {
        // 读配置，并定位索引文件
        AppConfig cfg = AppConfig.load();
        Path idx = cfg.dataDir().resolve("index.json");
        if (!Files.exists(idx)) {
            // 没有索引就没法检索，提示先建库
            System.out.println("索引不存在，先运行: index");
            System.exit(1);
        }
        // 把索引从文件读回内存
        VectorStore store = VectorStore.load(idx);
        if (store.size() == 0) {
            // 索引是空的，同样先建库
            System.out.println("索引为空，先运行: index");
            System.exit(1);
        }
        // 准备向量化客户端
        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());

        // 检索：把问题变成向量，再找最像的几条
        List<Scored> hits = new Retriever(embedder, store).retrieve(question, cfg.topK());
        System.out.println("检索命中 (共 " + store.size() + " 条索引, topK=" + cfg.topK()
                + ", embedding tokens=" + embedder.totalTokens() + "):");
        if (hits.isEmpty()) {
            System.out.println("  没有命中任何内容");
            return;
        }
        for (Scored s : hits) {
            // 只显示文件名，不显示整条路径
            String name = Path.of(s.chunk().sourcePath()).getFileName().toString();
            // 打印分数和来源的行号范围
            System.out.printf("  %.4f  %s:%d-%d行%n",
                    s.score(), name, s.chunk().lineFrom(), s.chunk().lineTo());
            // 预览正文：换行换成空格，太长就截断
            String preview = s.chunk().text().replace("\n", " ");
            if (preview.length() > 60) preview = preview.substring(0, 60) + "...";
            System.out.println("        " + preview);
        }
        // 告诉使用者当前门槛，方便判断该调参数还是补笔记
        System.out.println("当前门槛 minScore=" + cfg.minScore()
                + "，低于它的片段会被 ask 丢弃并回答\"资料中未提及\"");
        // 给使用者的调参提示
        System.out.println("提示: 分数普遍 <0.4 说明没检到，调 chunkChars/topK；检索对了但答案不对，才去调 prompt");
    }

    // ---------- eval: 跑测评集，只算检索指标 ----------

    private static void eval(String setFile) {
        AppConfig cfg = AppConfig.load();
        Path setPath = Path.of(setFile);
        if (!Files.exists(setPath)) {
            // 数据集文件都没有，先提示去哪找
            System.out.println("测评集不存在: " + setPath.toAbsolutePath());
            System.exit(1);
        }
        // 测评用的是已有索引，没建过索引就跑不了
        Path idx = cfg.dataDir().resolve("index.json");
        if (!Files.exists(idx)) {
            System.out.println("索引不存在，先运行: index");
            System.exit(1);
        }
        VectorStore store = VectorStore.load(idx);

        // 评测只需要向量化模型；对话模型完全不参与，所以不花钱在生成上
        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());

        List<EvalRunner.Question> questions;
        try {
            questions = EvalRunner.load(setPath);
        } catch (Exception e) {
            throw new IllegalStateException("读取测评集失败: " + e.getMessage(), e);
        }

        EvalRunner runner = new EvalRunner(embedder, store);
        List<EvalRunner.Outcome> outcomes = runner.run(questions, cfg.topK());
        runner.printReport(outcomes, cfg.topK(), cfg.minScore());
    }

    // ---------- ask: 问答 ----------

    private static void ask(String question) {
        // 读配置，并定位索引文件
        AppConfig cfg = AppConfig.load();
        Path idx = cfg.dataDir().resolve("index.json");
        if (!Files.exists(idx)) {
            // 没有索引就没法检索
            System.out.println("索引不存在，先运行: index");
            System.exit(1);
        }
        VectorStore store = VectorStore.load(idx);
        if (store.size() == 0) {
            // 索引是空的
            System.out.println("索引为空，先运行: index");
            System.exit(1);
        }

        // 向量化用一套 key 和模型
        EmbeddingClient embedder = new EmbeddingClient(
                new HttpTransport(cfg.embedBaseUrl(), cfg.embedApiKey(), cfg.debug()),
                cfg.embedModel(), cfg.embedBatch());
        // 对话可能用的是另一家，单独再建一个客户端
        ChatClient chat = new ChatClient(
                new HttpTransport(cfg.chatBaseUrl(), cfg.chatApiKey(), cfg.debug()),
                cfg.chatModel());

        // 先检索出最相关的几条资料
        List<Scored> raw = new Retriever(embedder, store).retrieve(question, cfg.topK());
        if (raw.isEmpty()) {
            System.out.println("没有检索到任何内容");
            return;
        }
        if (cfg.debug()) {
            // 调试模式下把每条得分和编号打到错误流
            for (Scored s : raw) {
                System.err.printf("[debug] %.4f %s%n", s.score(), s.chunk().id());
            }
        }
        // 低于分数门槛的片段直接丢掉，避免把无关内容硬塞给模型
        List<Scored> hits = raw.stream().filter(s -> s.score() >= cfg.minScore()).toList();
        if (hits.isEmpty()) {
            // 一条都不够格，说明笔记里没有相关内容。这时直接说不知道，不调模型：省 token，也不会瞎编
            System.out.println("答案:");
            System.out.println("资料中未提及");
            System.out.printf("（最高相似度 %.4f 低于门槛 %.2f，用 embed-check 可以看真实分数）%n",
                    raw.get(0).score(), cfg.minScore());
            System.out.println("本次 tokens: embedding=" + embedder.totalTokens() + ", chat=0（未调用模型）");
            return;
        }

        // 把资料和问题拼成 prompt，再让模型回答
        String[] p = PromptBuilder.build(hits, question);
        String answer = chat.chat(p[0], p[1]);

        System.out.println("答案:");
        System.out.println(answer);
        System.out.println("\n命中来源:");
        for (Scored s : hits) {
            // 只显示文件名和起始行，方便回原文核对
            String name = Path.of(s.chunk().sourcePath()).getFileName().toString();
            System.out.printf("  %.4f  %s:%d行%n", s.score(), name, s.chunk().lineFrom());
        }
        // 最后报告这次问答一共花了多少 token
        System.out.println("本次 tokens: embedding=" + embedder.totalTokens()
                + ", chat=" + chat.totalTokens());
    }

    // ---------- selftest: 网络层自检 ----------

    private static void selftest() {
        // JSON 工具和配置
        ObjectMapper mapper = new ObjectMapper();
        AppConfig cfg = AppConfig.load();
        // 记录通过和失败的次数
        int pass = 0, fail = 0;

        // 1. chat 正常调用
        try {
            ChatClient chat = new ChatClient(
                    new HttpTransport(cfg.chatBaseUrl(), cfg.chatApiKey(), cfg.debug()),
                    cfg.chatModel());
            // 让模型只回两个字，方便判断通不通
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
            // 只算一条，顺便看看向量有多长
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
            // 故意用一个假 key，看服务端会不会马上拒绝
            HttpTransport bad = new HttpTransport(cfg.chatBaseUrl(), "sk-invalid-key", false);
            // 手工拼一个最简单的请求体
            ObjectNode body = mapper.createObjectNode().put("model", cfg.chatModel());
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            bad.post("/chat/completions", body, 15);
            System.out.println("FAIL [401] 假 key 竟然成功了，检查 baseUrl 是否指向无需认证的服务");
            fail++;
        } catch (ApiException e) {
            if (e.statusCode == 401 || e.statusCode == 400) {
                // 401/400 正是我们期待的
                System.out.println("PASS [401] 假 key 被立刻拒绝, 状态码=" + e.statusCode);
                pass++;
            } else {
                System.out.println("FAIL [401] 期望 401/400, 实际 " + e.statusCode);
                fail++;
            }
        }

        // 4. 不存在的路径: 404, 同样不重试
        try {
            // 用真 key 去请求一个不存在的接口
            HttpTransport good = new HttpTransport(cfg.chatBaseUrl(), cfg.chatApiKey(), false);
            good.post("/nonexistent-path", mapper.createObjectNode(), 15);
            System.out.println("FAIL [404] 不存在的路径竟然成功了");
            fail++;
        } catch (ApiException e) {
            if (e.statusCode == 404) {
                // 404 就是我们要的
                System.out.println("PASS [404] 正确返回 404");
                pass++;
            } else {
                System.out.println("FAIL [404] 期望 404, 实际 " + e.statusCode);
                fail++;
            }
        }

        // 打印总结果
        System.out.printf("%n自检结果: %d 通过, %d 失败%n", pass, fail);
        // 有失败就以失败状态退出
        if (fail > 0) System.exit(1);
    }
}
