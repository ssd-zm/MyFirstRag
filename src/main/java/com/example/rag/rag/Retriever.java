package com.example.rag.rag;

import com.example.rag.llm.EmbeddingClient;
import com.example.rag.search.Bm25Index;
import com.example.rag.search.RrfFusion;
import com.example.rag.store.Scored;
import com.example.rag.store.VectorStore;

import java.util.List;

/**
 * 检索器：问题清洗 -> 向量化 -> 向量库 topK。
 * 只依赖 VectorStore 的方法签名，不关心底层是 List 还是 pgvector。
 * <p>
 * 支持三种检索模式：
 *   VECTOR_ONLY  —— 纯向量检索（原来的行为，默认）
 *   BM25_ONLY    —— 纯关键词检索，适合专有名词、类名、错误码
 *   HYBRID       —— 两路各取前 20，RRF 融合后取 topK（推荐）
 */
public class Retriever {

    // 三种检索模式
    public enum SearchMode {
        VECTOR_ONLY,   // 纯向量，语义相似优先
        BM25_ONLY,     // 纯关键词，精确匹配优先
        HYBRID         // 混合，两路各取 20 条再融合
    }

    // 用来把问题变成一串数字（向量）。BM25_ONLY 模式下可以为 null
    private final EmbeddingClient embedder;
    // 已经建好的向量库，用来找最相似的资料
    private final VectorStore store;
    // 已经建好的 BM25 索引，HYBRID 和 BM25_ONLY 模式下必须不为 null
    private final Bm25Index bm25;
    // 当前检索模式
    private final SearchMode mode;

    /**
     * 向后兼容构造：默认 VECTOR_ONLY，只需要 embedder + store。
     * 所有现有调用都不用改。
     */
    public Retriever(EmbeddingClient embedder, VectorStore store) {
        this(embedder, store, null, SearchMode.VECTOR_ONLY);
    }

    /**
     * 全功能构造：指定模式和可选的 BM25 索引。
     * HYBRID 和 BM25_ONLY 模式下 bm25 必须非空，否则抛 IllegalArgumentException。
     */
    public Retriever(EmbeddingClient embedder, VectorStore store, Bm25Index bm25, SearchMode mode) {
        this.embedder = embedder;
        this.store = store;
        this.bm25 = bm25;
        this.mode = mode;

        if ((mode == SearchMode.HYBRID || mode == SearchMode.BM25_ONLY) && bm25 == null) {
            // BM25 相关模式没有索引就没意义，提前报错比查到一半才发现好
            throw new IllegalArgumentException(
                    mode + " 模式需要传入 Bm25Index，但当前为 null");
        }
        if ((mode == SearchMode.VECTOR_ONLY || mode == SearchMode.HYBRID) && embedder == null) {
            // 向量相关模式没有 embedder 也没法跑
            throw new IllegalArgumentException(
                    mode + " 模式需要传入 EmbeddingClient，但当前为 null");
        }
    }

    public List<Scored> retrieve(String question, int topK) {
        // 先把问题里的客套话和问号去掉，减少干扰
        String q = clean(question);

        // 根据模式走不同路径
        return switch (mode) {
            case VECTOR_ONLY -> vectorOnly(q, topK);
            case BM25_ONLY -> bm25Only(q, topK);
            case HYBRID -> hybrid(q, topK);
        };
    }

    // 纯向量：向量化 → 余弦相似度 topK
    private List<Scored> vectorOnly(String q, int topK) {
        float[] qv = embedder.embedOne(q);
        return store.search(qv, topK);
    }

    // 纯 BM25：分词 → 词频打分 topK（不调 embedding）
    private List<Scored> bm25Only(String q, int topK) {
        return bm25.search(q, topK);
    }

    // 混合：向量和 BM25 各取前 20（RRF 融合只看前 20 名贡献就够了），
    //      用 RRF 融合排名，再截 topK
    private List<Scored> hybrid(String q, int topK) {
        // 向量那路：向量化后取前 20，融合时排名越靠前加分越多
        float[] qv = embedder.embedOne(q);
        List<Scored> vectorHits = store.search(qv, 20);
        // BM25 那路：取前 20，不调 embedding
        List<Scored> bm25Hits = bm25.search(q, 20);
        // RRF 融合两路排名，取最终的 topK
        return RrfFusion.fuse(List.of(vectorHits, bm25Hits), topK);
    }

    /** 去掉口语前缀和问号，降低它们对 query 向量的干扰 */
    static String clean(String q) {
        // 去掉首尾空格
        return q.trim()
                // 去掉"请问""帮我查一下"这类开头
                .replaceFirst("^(帮我查一下|请问一下|请问|麻烦|我想知道|我想问|帮我看看|帮我|我想|请)+", "")
                // 问号本身没有语义，换成空格
                .replace("？", " ")
                .replace("?", " ")
                // 最后再去一次首尾空白
                .trim();
    }
}
