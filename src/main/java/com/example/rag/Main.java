package com.example.rag;

import com.example.rag.config.AppConfig;
import com.example.rag.http.ApiException;
import com.example.rag.http.HttpTransport;
import com.example.rag.ingest.MarkdownLoader;
import com.example.rag.ingest.TextSplitter;
import com.example.rag.llm.ChatClient;
import com.example.rag.llm.EmbeddingClient;
import com.example.rag.rag.PromptBuilder;
import com.example.rag.rag.Retriever;
import com.example.rag.selftest.NetworkSelfTest;
import com.example.rag.store.Chunk;
import com.example.rag.store.InMemoryVectorStore;
import com.example.rag.store.VectorStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Main {

    private static final Path NOTES_DIR = Paths.get("notes");
    private static final Path DATA_DIR = Paths.get("data");

    public static void main(String[] args) {
        try {
            int code = dispatch(args);
            if (code != 0) {
                System.exit(code);
            }
        } catch (ApiException e) {
            System.err.println("[error] " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[error] " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int dispatch(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 2;
        }
        return switch (args[0]) {
            case "selftest" -> NetworkSelfTest.run();
            case "index" -> {
                index(args.length > 1 ? args[1] : NOTES_DIR.toString());
                yield 0;
            }
            case "split" -> {
                split(args.length > 1 ? args[1] : NOTES_DIR.toString());
                yield 0;
            }
            case "ask" -> {
                ask(join(args, 1));
                yield 0;
            }
            case "embed-check" -> {
                embedCheck(join(args, 1));
                yield 0;
            }
            case "help", "-h", "--help" -> {
                usage();
                yield 0;
            }
            default -> {
                System.out.println("未知命令：" + args[0]);
                usage();
                yield 2;
            }
        };
    }

    private static void index(String notesDir) throws Exception {
        AppConfig config = AppConfig.load();
        config.requireApiKey();
        HttpTransport http = transport(config, Duration.ofSeconds(60));
        EmbeddingClient embedding = new EmbeddingClient(http, config);
        TextSplitter splitter = new TextSplitter(config.chunkChars(), config.overlapChars());
        MarkdownLoader loader = new MarkdownLoader();

        List<MarkdownLoader.Doc> docs = loader.load(Paths.get(notesDir));
        List<Chunk> chunks = new ArrayList<>();
        for (MarkdownLoader.Doc doc : docs) {
            List<TextSplitter.Piece> pieces = splitter.split(doc.content());
            for (int i = 0; i < pieces.size(); i++) {
                TextSplitter.Piece piece = pieces.get(i);
                String id = doc.source() + "#" + (i + 1);
                chunks.add(new Chunk(id, doc.source(), piece.lineFrom(), piece.text(), null));
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("没有切出任何片段，检查语料内容是否为空");
        }
        int totalChars = chunks.stream().mapToInt(c -> c.text().length()).sum();
        System.out.printf("文档 %d 篇 -> 切片 %d 个（平均 %d 字，chunkChars=%d overlap=%d）%n",
                docs.size(), chunks.size(), totalChars / chunks.size(),
                config.chunkChars(), config.overlapChars());

        float[][] vectors = embedding.embed(chunks.stream().map(Chunk::text).toList());
        List<Chunk> indexed = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            indexed.add(chunks.get(i).withVector(vectors[i]));
        }

        InMemoryVectorStore store = new InMemoryVectorStore();
        store.addAll(indexed);
        store.save(DATA_DIR);
        System.out.printf("索引已保存到 %s（%d 个切片，%d 维）%n",
                DATA_DIR.toAbsolutePath(), store.size(), store.dimension());
    }

    /** 不调用任何接口，只验证语料读取与切分，方便先把离线链路跑对。 */
    private static void split(String notesDir) throws Exception {
        AppConfig config = AppConfig.load();
        TextSplitter splitter = new TextSplitter(config.chunkChars(), config.overlapChars());
        MarkdownLoader loader = new MarkdownLoader();

        List<MarkdownLoader.Doc> docs = loader.load(Paths.get(notesDir));
        List<Chunk> chunks = new ArrayList<>();
        for (MarkdownLoader.Doc doc : docs) {
            List<TextSplitter.Piece> pieces = splitter.split(doc.content());
            System.out.printf("%-28s %d 个切片%n", doc.source(), pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                TextSplitter.Piece piece = pieces.get(i);
                chunks.add(new Chunk(doc.source() + "#" + (i + 1), doc.source(),
                        piece.lineFrom(), piece.text(), null));
            }
        }
        int totalChars = chunks.stream().mapToInt(chunk -> chunk.text().length()).sum();
        System.out.printf("合计 %d 个切片，平均 %d 字（chunkChars=%d overlap=%d）%n",
                chunks.size(), totalChars / Math.max(1, chunks.size()),
                config.chunkChars(), config.overlapChars());

        int samples = Math.min(3, chunks.size());
        for (int i = 0; i < samples; i++) {
            Chunk chunk = chunks.get(i);
            System.out.printf("%n  [%s] 起始行 %d，%d 字%n    %s%n",
                    chunk.id(), chunk.lineFrom(), chunk.text().length(), Retriever.preview(chunk, 80));
        }
    }

    private static void ask(String question) throws Exception {
        if (question.isBlank()) {
            System.out.println("用法：ask \"你的问题\"");
            return;
        }
        AppConfig config = AppConfig.load();
        config.requireApiKey();
        HttpTransport http = transport(config, Duration.ofSeconds(60));

        InMemoryVectorStore store = InMemoryVectorStore.load(DATA_DIR);
        EmbeddingClient embedding = new EmbeddingClient(http, config);
        Retriever retriever = new Retriever(embedding, store);

        List<VectorStore.Hit> hits = retriever.retrieve(question, config.topK());
        System.out.println("问题：" + question);
        System.out.println("命中片段：");
        for (int i = 0; i < hits.size(); i++) {
            VectorStore.Hit hit = hits.get(i);
            System.out.printf("  [%d] score=%.4f  %s:%d  %s%n", i + 1, hit.score(),
                    hit.chunk().source(), hit.chunk().lineFrom(), Retriever.preview(hit.chunk(), 60));
        }

        String prompt = new PromptBuilder().build(question, hits);
        String answer = new ChatClient(http, config).chat(prompt);
        System.out.println();
        System.out.println("回答：");
        System.out.println(answer);
    }

    private static void embedCheck(String text) throws Exception {
        if (text.isBlank()) {
            text = "缓存穿透可以用布隆过滤器或空值缓存来兜底";
        }
        AppConfig config = AppConfig.load();
        config.requireApiKey();
        HttpTransport http = transport(config, Duration.ofSeconds(30));
        float[] vector = new EmbeddingClient(http, config).embedOne(text);
        System.out.printf("模型 %s 返回 %d 维向量%n", config.embedModel(), vector.length);
        System.out.print("前 8 个分量：");
        for (int i = 0; i < Math.min(8, vector.length); i++) {
            System.out.printf("%.4f ", vector[i]);
        }
        System.out.println();
    }

    private static HttpTransport transport(AppConfig config, Duration timeout) {
        return new HttpTransport(config.baseUrl(), config.apiKey(), timeout, 4, 500, config.debug());
    }

    private static String join(String[] args, int from) {
        if (args.length <= from) {
            return "";
        }
        String joined = String.join(" ", Arrays.copyOfRange(args, from, args.length)).strip();
        if (joined.length() >= 2 && joined.startsWith("\"") && joined.endsWith("\"")) {
            joined = joined.substring(1, joined.length() - 1);
        }
        return joined;
    }

    private static void usage() {
        System.out.println("""
                用法：java -jar rag-demo.jar <命令>
                  selftest              本地自检网络层（不需要 API Key）
                  embed-check "文本"    打印向量维度，验证 embedding 接口
                  index [目录]          建索引，默认读 notes/
                  split [目录]          只切分并打印结果，不调接口、不需要 Key
                  ask "问题"            检索 + 生成回答
                  help                  显示本帮助
                """);
    }
}
