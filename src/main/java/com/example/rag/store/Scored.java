package com.example.rag.store;

/** 检索结果：chunk + 余弦相似度分数。 */
public record Scored(
        // 命中的那一片资料（切片）
        Chunk chunk,
        // 这片资料和问题有多相似，数字越大越相关，一般在 -1 到 1 之间
        double score) {}
