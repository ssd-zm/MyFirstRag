package com.example.rag.ingest;

import java.nio.file.Path;

/** 一个已读入内存的文本文件。 */
public record FileText(Path path, String content) {}
