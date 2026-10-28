package com.example.rag.store;

import java.util.List;

public interface VectorStore {

    void addAll(List<Chunk> chunks);

    List<Hit> search(float[] queryVector, int topK);

    int size();

    record Hit(Chunk chunk, double score) {
    }
}
