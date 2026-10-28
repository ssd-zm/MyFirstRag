package com.example.rag.store;

/** 检索结果：chunk + 余弦相似度分数。 */
public record Scored(
        /** 命中的切片，其 text 会被拼进 prompt，sourcePath/lineFrom 用于生成引用 */
        Chunk chunk,
        /** 与问题的余弦相似度，取值 [-1,1]，越大越相关；纯向量打分，没有重排或加权 */
        double score) {}
