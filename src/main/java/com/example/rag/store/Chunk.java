package com.example.rag.store;

/**
 * 一个切片单元。vector 在建索引时填入，建之前为 null。
 */
public record Chunk(String id, String source, int lineFrom, String text, float[] vector) {

    public Chunk withVector(float[] newVector) {
        return new Chunk(id, source, lineFrom, text, newVector);
    }
}
