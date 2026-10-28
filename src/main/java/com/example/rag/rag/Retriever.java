package com.example.rag.rag;

import com.example.rag.llm.EmbeddingClient;
import com.example.rag.store.Chunk;
import com.example.rag.store.VectorStore;

import java.util.List;

public final class Retriever {

    private final EmbeddingClient embedding;
    private final VectorStore store;

    public Retriever(EmbeddingClient embedding, VectorStore store) {
        this.embedding = embedding;
        this.store = store;
    }

    public List<VectorStore.Hit> retrieve(String question, int topK) {
        float[] queryVector = embedding.embedOne(question);
        List<VectorStore.Hit> hits = store.search(queryVector, topK);
        if (hits.isEmpty()) {
            throw new IllegalStateException("索引为空，请先执行 index 命令");
        }
        return hits;
    }

    public static String preview(Chunk chunk, int maxChars) {
        String text = chunk.text().replaceAll("\\s+", " ");
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}
