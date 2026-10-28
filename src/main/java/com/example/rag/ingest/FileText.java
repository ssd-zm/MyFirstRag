package com.example.rag.ingest;

import java.nio.file.Path;

/** 一个已读入内存的文本文件。 */
public record FileText(
        /** 文件磁盘路径，切分时写入 Chunk.sourcePath，引用展示时只取其中的文件名 */
        Path path,
        /** 文件完整文本，已按 UTF-8 解码，切分器按行扫描它 */
        String content) {}
