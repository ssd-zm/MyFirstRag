package com.example.rag.rag;

import com.example.rag.llm.EmbeddingClient;
import com.example.rag.store.Scored;
import com.example.rag.store.VectorStore;

import java.util.List;

/**
 * 检索器：问题清洗 -> 向量化 -> 向量库 topK。
 * 只依赖 VectorStore 的方法签名，不关心底层是 List 还是 pgvector。
 */
public class Retriever {

    /** 向量化客户端，retrieve 时把清洗后的问题转成查询向量，会消耗 embedding tokens */
    private final EmbeddingClient embedder;
    /** 已加载的向量库，只用到 search(float[], int) 这一签名，不关心底层存储实现 */
    private final VectorStore store;

    public Retriever(EmbeddingClient embedder, VectorStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    public List<Scored> retrieve(String question, int topK) {
        String q = clean(question);
        float[] qv = embedder.embedOne(q);
        return store.search(qv, topK);
    }

    /** 去掉口语前缀和问号，降低它们对 query 向量的干扰 */
    static String clean(String q) {
        return q.trim()
                .replaceFirst("^(帮我查一下|请问一下|请问|麻烦|我想知道|我想问|帮我看看|帮我|我想|请)+", "")
                .replace("？", " ")
                .replace("?", " ")
                .trim();
    }
}
