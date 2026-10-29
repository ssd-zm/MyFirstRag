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

    // 用来把问题变成一串数字（向量）
    private final EmbeddingClient embedder;
    // 已经建好的向量库，用来找最相似的资料
    private final VectorStore store;

    public Retriever(EmbeddingClient embedder, VectorStore store) {
        // 把传进来的对象保存到字段里
        this.embedder = embedder;
        this.store = store;
    }

    public List<Scored> retrieve(String question, int topK) {
        // 先把问题里的客套话和问号去掉，减少干扰
        String q = clean(question);
        // 把问题也变成一串数字，才能和资料比相似度
        float[] qv = embedder.embedOne(q);
        // 去库里找最像的前 topK 条
        return store.search(qv, topK);
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
