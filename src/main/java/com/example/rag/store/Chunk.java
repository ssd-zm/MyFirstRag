package com.example.rag.store;

/**
 * 一条不可变的索引数据。
 * lineFrom/lineTo 是 1-based 行号，用于答案引用；vector 在向量化之后填充。
 */
public record Chunk(
        /** 唯一标识，格式为 "文件名:起始行号"（如 a.md:12），只在 debug 日志里用来区分切片 */
        String id,
        /** 源文件磁盘路径，来自 FileText.path；拼 prompt 与引用时取它的文件名部分 */
        String sourcePath,
        /** 1-based 起始行号，用于答案引用；由切分器把 0-based 行号 +1 得到 */
        int lineFrom,
        /** 1-based 结束行号；同段落按句拆出的多块共用段落行尾，引用可能有几行误差 */
        int lineTo,
        /** 送进 embedding 与 prompt 的正文，含 overlap 尾巴和标题前缀，不含行号 */
        String text,
        /** embedding 模型输出的稠密向量，长度即维度，一般已归一化（余弦退化为点积）；建索引前为 null，检索时跳过 */
        float[] vector) {}
