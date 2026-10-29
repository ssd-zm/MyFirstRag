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

    // 只认这几种后缀，其它文件（图片、pdf 等）一律跳过
    private static final String[] EXTENSIONS = {".md", ".markdown", ".txt"};

    public List<FileText> loadAll(Path notesDir) {
        // 目录不存在就早点报错，省得后面白跑一趟
        if (!Files.isDirectory(notesDir)) {
            throw new IllegalStateException("笔记目录不存在: " + notesDir.toAbsolutePath()
                    + "。请创建 notes/ 目录并放入 .md 文件");
        }
        // 用来装读到的文件，最后一起返回
        List<FileText> result = new ArrayList<>();
        // try-with-resources：用完会自动关闭这个目录流
        try (Stream<Path> walk = Files.walk(notesDir)) {
            // 只留普通文件，把目录本身去掉
            walk.filter(Files::isRegularFile)
                    // 只看 .md/.txt 这类文本文件
                    .filter(this::isTextFile)
                    // 跳过 .git 这种隐藏目录里的东西
                    .filter(this::notHidden)
                    // 排个序，保证每次建索引顺序都一样
                    .sorted()
                    .forEach(path -> {
                        try {
                            // 按 UTF-8 把整个文件读成一段文字
                            result.add(new FileText(path, Files.readString(path, StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            // 单个文件读不了就打个警告跳过，不影响其它文件
                            System.err.println("[warn] 跳过无法读取的文件: " + path + " (" + e.getMessage() + ")");
                        }
                    });
        } catch (IOException e) {
            // 整个目录都遍历不了，只能报错停下
            throw new IllegalStateException("遍历笔记目录失败: " + e.getMessage(), e);
        }
        return result;
    }

    private boolean isTextFile(Path p) {
        // 取文件名并统一转小写，这样 .MD 也能认出来
        String name = p.getFileName().toString().toLowerCase();
        // 拿允许的后缀逐个比对
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        // 一个都不匹配，说明不是想要的文本文件
        return false;
    }

    /** 跳过隐藏目录（.git/.obsidian 等）、target、node_modules */
    private boolean notHidden(Path p) {
        // 把路径的每一层都检查一遍
        for (Path part : p) {
            String n = part.toString();
            // 以 . 开头的是隐藏目录，target 和 node_modules 是编译产物或依赖，都不要
            if (n.startsWith(".") || n.equals("target") || n.equals("node_modules")) {
                return false;
            }
        }
        // 每一层都没问题，这个文件可以要
        return true;
    }
}
