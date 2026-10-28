package com.example.rag.store;

/** 检索结果：chunk + 余弦相似度分数。 */
public record Scored(Chunk chunk, double score) {}
