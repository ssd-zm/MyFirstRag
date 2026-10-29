package com.example.rag.ingest;

import java.nio.file.Path;

/** 一个已读入内存的文本文件。 */
public record FileText(
        // 这个文件在磁盘上的完整位置
        Path path,
        // 整个文件的文字内容，已经按 UTF-8 解码好了
        String content) {}
