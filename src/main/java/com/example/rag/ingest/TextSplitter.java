package com.example.rag.ingest;

import com.example.rag.store.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分器（三步）：
 * 1) 按标题与空行取段（段带所属标题与行号）
 * 2) 超过 chunkChars 的段按句末标点（。！？；!?;）细切，不能按字符数硬切中文
 * 3) 合并段为 <= chunkChars 的 chunk，并把上一块末尾 overlapChars 字作为下一块开头
 * <p>
 * 设计取舍：
 * - chunk 正文前缀所属标题，显著提升检索命中率（标题就是主题）
 * - 行号指向 chunk 自身内容起点（overlap/标题前缀不计入），引用定位足够准
 * - 超长段落的句子子块共用段落行号，引用可能有 ±几行误差，v1 可接受
 */
public class TextSplitter {

    private record Seg(String text, int start, int end, String heading) {}

    public List<Chunk> split(FileText file, int chunkChars, int overlapChars) {
        List<Seg> segs = paragraphs(file.content(), chunkChars);
        List<Chunk> out = new ArrayList<>();
        String fileName = file.path().getFileName().toString();
        String path = file.path().toString();

        StringBuilder cur = new StringBuilder();
        int curStart = -1, curEnd = -1;
        String curHeading = "";
        String prevTail = "";          // 上一块的末尾，用作同主题下一块的 overlap
        String prevTailHeading = "";

        for (Seg seg : segs) {
            boolean topicChanged = !seg.heading().equals(curHeading);
            boolean needFlush = cur.length() > 0
                    && (cur.length() + seg.text().length() + 1 > chunkChars || topicChanged);
            if (needFlush) {
                out.add(make(fileName, path, cur.toString(), curStart, curEnd, curHeading, prevTail));
                prevTail = overlapTail(cur.toString(), overlapChars);
                prevTailHeading = curHeading;
                cur.setLength(0);
            }
            if (cur.length() == 0) {
                curStart = seg.start();
                curHeading = seg.heading();
                if (!seg.heading().equals(prevTailHeading)) {
                    prevTail = "";     // 换主题了，上一主题的尾巴不能带过来
                }
            }
            cur.append(seg.text()).append('\n');
            curEnd = seg.end();
        }
        if (cur.length() > 0) {
            out.add(make(fileName, path, cur.toString(), curStart, curEnd, curHeading, prevTail));
        }
        return out;
    }

    private static Chunk make(String fileName, String path, String content,
                              int start0, int end0, String heading, String overlap) {
        StringBuilder body = new StringBuilder();
        if (!overlap.isEmpty()) body.append(overlap).append('\n');
        if (!heading.isEmpty()) body.append(heading).append('\n');
        body.append(content.strip());
        int from = start0 + 1;     // 0-based 转 1-based
        int to = end0 + 1;
        return new Chunk(fileName + ":" + from, path, from, to, body.toString(), null);
    }

    private static String overlapTail(String text, int n) {
        String t = text.strip();
        if (n <= 0 || t.length() <= n) return "";
        return t.substring(t.length() - n);
    }

    // ---------- 第 1、2 步：取段 ----------

    private static List<Seg> paragraphs(String content, int chunkChars) {
        List<Seg> segs = new ArrayList<>();
        String[] lines = content.split("\\R");
        String heading = "";
        StringBuilder buf = new StringBuilder();
        int start = -1, end = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.matches("#{1,6}\\s+.*")) {            // Markdown 标题 = 新主题
                flush(segs, buf, start, end, heading, chunkChars);
                heading = line;
                start = -1;
                continue;
            }
            if (line.isBlank()) {                          // 空行 = 段落边界
                flush(segs, buf, start, end, heading, chunkChars);
                start = -1;
                continue;
            }
            if (start < 0) start = i;
            end = i;
            if (buf.length() > 0) buf.append('\n');
            buf.append(line);
            if (buf.length() >= chunkChars) {              // 超长段落兜底切
                flush(segs, buf, start, end, heading, chunkChars);
                start = -1;
            }
        }
        flush(segs, buf, start, end, heading, chunkChars);
        return segs;
    }

    private static void flush(List<Seg> segs, StringBuilder buf, int start, int end,
                              String heading, int chunkChars) {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (start < 0 || text.length() < 4) return;        // 丢弃过短碎屑(如 "---")
        if (text.length() > chunkChars) {
            for (String piece : sentenceSplit(text, chunkChars)) {
                segs.add(new Seg(piece, start, end, heading));
            }
        } else {
            segs.add(new Seg(text, start, end, heading));
        }
    }

    /** 按句末标点切（保留标点在句尾），单句超长时允许超出 chunkChars */
    private static List<String> sentenceSplit(String text, int chunkChars) {
        List<String> pieces = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String s : text.split("(?<=[。！？；!?;])")) {
            if (b.length() > 0 && b.length() + s.length() > chunkChars) {
                pieces.add(b.toString());
                b.setLength(0);
            }
            b.append(s);
        }
        if (b.length() > 0) pieces.add(b.toString());
        return pieces;
    }
}
