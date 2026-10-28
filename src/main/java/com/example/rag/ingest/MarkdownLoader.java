package com.example.rag.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 遍历笔记目录，读取全部文本文件。
 * 必须显式 UTF-8（Windows 默认 GBK 会把中文笔记读成乱码）；
 * 单个文件读取失败只跳过并告警，不让整体索引崩掉。
 */
public class MarkdownLoader {

    /** 允许入索引的扩展名白名单，逐个做小写后缀匹配；不在名单内的文件（含图片、pdf）直接跳过 */
    private static final String[] EXTENSIONS = {".md", ".markdown", ".txt"};

    public List<FileText> loadAll(Path notesDir) {
        if (!Files.isDirectory(notesDir)) {
            throw new IllegalStateException("笔记目录不存在: " + notesDir.toAbsolutePath()
                    + "。请创建 notes/ 目录并放入 .md 文件");
        }
        List<FileText> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(notesDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(this::isTextFile)
                    .filter(this::notHidden)
                    .sorted()
                    .forEach(path -> {
                        try {
                            result.add(new FileText(path, Files.readString(path, StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            System.err.println("[warn] 跳过无法读取的文件: " + path + " (" + e.getMessage() + ")");
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("遍历笔记目录失败: " + e.getMessage(), e);
        }
        return result;
    }

    private boolean isTextFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /** 跳过隐藏目录（.git/.obsidian 等）、target、node_modules */
    private boolean notHidden(Path p) {
        for (Path part : p) {
            String n = part.toString();
            if (n.startsWith(".") || n.equals("target") || n.equals("node_modules")) {
                return false;
            }
        }
        return true;
    }
}
