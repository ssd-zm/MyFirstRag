package com.example.rag.store;

/**
 * 一条不可变的索引数据。
 * lineFrom/lineTo 是 1-based 行号，用于答案引用；vector 在向量化之后填充。
 */
public record Chunk(String id, String sourcePath, int lineFrom, int lineTo, String text, float[] vector) {}
