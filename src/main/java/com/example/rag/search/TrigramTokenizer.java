package com.example.rag.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最简单可行的中文 bigram 分词器，不引任何第三方库。
 * 目的：给 BM25 提供 term 列表，不追求分词精度，追求一致可复现。
 * <p>
 * 规则：
 *   1. 英文和数字连在一起的整串按一个 term 处理（camelCase、snake_case、429、HTTP 都算）
 *   2. 剩下的纯中文片段切成 bigram（连续两个汉字一个 term）
 *   3. 标点、空格、单个汉字丢弃，不贡献 term
 * <p>
 * 为什么不用 jieba / HanLP：
 *   - 引入重依赖，增加学习成本
 *   - 本项目是教学演示，bigram 足够支撑小规模 BM25
 *   - 真正要上生产再换，接口层已经隔离
 */
public final class TrigramTokenizer {

    // 匹配连续的英文、数字、下划线、连字符，例如 connectTimeout, 429, Retry-After
    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-zA-Z0-9_\\-]+");

    private TrigramTokenizer() {}

    /** 把一段文本切成 term 列表，顺序和原文一致，不做去重（BM25 需要词频） */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> terms = new ArrayList<>();
        // 用 ASCII 正则找出所有英文/数字段，剩下的就是中文/标点
        Matcher m = ASCII_TOKEN.matcher(text);
        int cursor = 0;
        while (m.find()) {
            // 先处理上一个 ASCII 段和当前 ASCII 段之间的中文部分
            if (m.start() > cursor) {
                addBigrams(text, cursor, m.start(), terms);
            }
            // 英文/数字整段作为一个 term
            terms.add(m.group().toLowerCase());
            cursor = m.end();
        }
        // 最后剩余的中文部分
        if (cursor < text.length()) {
            addBigrams(text, cursor, text.length(), terms);
        }
        return terms;
    }

    /**
     * 把 [start, end) 范围内的中文切成 bigram。
     * 只收集连续的 CJK 字符，标点和单字跳过。
     */
    private static void addBigrams(String text, int start, int end, List<String> sink) {
        int runStart = -1;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                if (runStart < 0) runStart = i;
            } else {
                // 遇到非 CJK，把上一段 CJK 切成 bigram
                if (runStart >= 0) {
                    for (int j = runStart; j <= i - 2; j++) {
                        sink.add(text.substring(j, j + 2));
                    }
                    runStart = -1;
                }
            }
        }
        // 尾部 CJK 段同样处理
        if (runStart >= 0) {
            for (int j = runStart; j <= end - 2; j++) {
                sink.add(text.substring(j, j + 2));
            }
        }
    }

    /** 简单判定：字符 Unicode 落在 CJK 统一表意文字范围内 */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}