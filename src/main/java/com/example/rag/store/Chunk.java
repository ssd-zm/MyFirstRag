package com.example.rag.store;

/**
 * 一条不可变的索引数据。
 * lineFrom/lineTo 是 1-based 行号，用于答案引用；vector 在向量化之后填充。
 */
public record Chunk(
        // 唯一编号，格式是"文件名:起始行号"，比如 a.md:12，调试时用来区分不同切片
        String id,
        // 这个切片来自哪个文件（磁盘上的路径）
        String sourcePath,
        // 切片在原文里从第几行开始，从 1 开始数，写引用时用
        int lineFrom,
        // 切片在原文里到第几行结束，也从 1 开始数
        int lineTo,
        // 切片正文，也就是要送进模型的这段文字
        String text,
        // 这段文字对应的一串数字，用数字表示它的意思；建索引前是 null
        float[] vector) {}
