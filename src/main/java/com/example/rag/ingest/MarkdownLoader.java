package com.example.rag.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 只做一件事：把目录下的 .md 文件读成 (相对路径, 内容)。
 */
public final class MarkdownLoader {

    public record Doc(String source, String content) {
    }

    public List<Doc> load(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("语料目录不存在：" + dir.toAbsolutePath());
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("目录里没有 .md 文件：" + dir.toAbsolutePath());
        }
        List<Doc> docs = new ArrayList<>(files.size());
        for (Path file : files) {
            String source = dir.relativize(file).toString().replace('\\', '/');
            docs.add(new Doc(source, Files.readString(file, StandardCharsets.UTF_8)));
        }
        return docs;
    }
}
