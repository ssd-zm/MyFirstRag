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

    private record Seg(
            // 这一段的文字，已经去掉首尾空白
            String text,
            // 这一段在原文里从第几行开始，这里从 0 开始数
            int start,
            // 这一段在原文里到第几行结束，这里从 0 开始数
            int end,
            // 这一段属于哪个标题（带 # 的整行），空串表示在第一个标题之前
            String heading) {}

    public List<Chunk> split(FileText file, int chunkChars, int overlapChars) {
        // 第一步：先把整篇文章按标题和空行切成一段一段
        List<Seg> segs = paragraphs(file.content(), chunkChars);
        // 装最终要返回的切片
        List<Chunk> out = new ArrayList<>();
        // 拼编号、记录来源都要用到文件名和完整路径
        String fileName = file.path().getFileName().toString();
        String path = file.path().toString();

        // cur 正在攒的这一块内容，curStart/curEnd 是它的起止行号，-1 表示还没开始
        StringBuilder cur = new StringBuilder();
        int curStart = -1, curEnd = -1;
        // 当前这一块属于哪个标题
        String curHeading = "";
        // 上一块的末尾，用作同主题下一块的 overlap
        String prevTail = "";
        // 上一块的标题，用来判断是不是换了主题
        String prevTailHeading = "";

        for (Seg seg : segs) {
            // 标题变了就说明换主题了
            boolean topicChanged = !seg.heading().equals(curHeading);
            // 再放这一段就超长，或者换了主题，就该先把当前这块存下来
            boolean needFlush = cur.length() > 0
                    && (cur.length() + seg.text().length() + 1 > chunkChars || topicChanged);
            if (needFlush) {
                // 生成一条 Chunk 放进结果里
                out.add(make(fileName, path, cur.toString(), curStart, curEnd, curHeading, prevTail));
                // 记下这块最后几个字，下一块开头要重复它们，让意思接得上
                prevTail = overlapTail(cur.toString(), overlapChars);
                prevTailHeading = curHeading;
                // 清空，准备攒下一块
                cur.setLength(0);
            }
            if (cur.length() == 0) {
                // 新的一块从这里开始，记下行号和标题
                curStart = seg.start();
                curHeading = seg.heading();
                if (!seg.heading().equals(prevTailHeading)) {
                    // 换主题了，上一主题的尾巴不能带过来
                    prevTail = "";
                }
            }
            // 把这一段接到当前块后面，用换行隔开
            cur.append(seg.text()).append('\n');
            // 更新结束行号
            curEnd = seg.end();
        }
        // 循环结束后，最后一块还没存的话也要存下来
        if (cur.length() > 0) {
            out.add(make(fileName, path, cur.toString(), curStart, curEnd, curHeading, prevTail));
        }
        return out;
    }

    private static Chunk make(String fileName, String path, String content,
                              int start0, int end0, String heading, String overlap) {
        // 真正存进 chunk 的正文，前面可能要加一块尾巴和标题
        StringBuilder body = new StringBuilder();
        // 有上一块的尾巴就先放进来，让上下文接得上
        if (!overlap.isEmpty()) body.append(overlap).append('\n');
        // 有标题就放进来，标题本身就是这段的主题，能提高检索命中率
        if (!heading.isEmpty()) body.append(heading).append('\n');
        // 最后放正文，strip 去掉首尾空白
        body.append(content.strip());
        // 行号从"从 0 数"改成日常习惯的"从 1 数"
        int from = start0 + 1;
        int to = end0 + 1;
        // 编号用"文件名:起始行"的形式，一眼就能看出这条从哪来
        return new Chunk(fileName + ":" + from, path, from, to, body.toString(), null);
    }

    private static String overlapTail(String text, int n) {
        // 先去掉首尾空白
        String t = text.strip();
        // 不需要 overlap，或者文字本身比要留的还短，就返回空串
        if (n <= 0 || t.length() <= n) return "";
        // 取最后 n 个字
        return t.substring(t.length() - n);
    }

    // ---------- 第 1、2 步：取段 ----------

    private static List<Seg> paragraphs(String content, int chunkChars) {
        // 装切出来的段落
        List<Seg> segs = new ArrayList<>();
        // 按换行拆成一行一行，\R 能同时兼容 \n 和 \r\n
        String[] lines = content.split("\\R");
        // 当前所在的标题，一开始还没有标题
        String heading = "";
        // buf 用来攒当前这一段的内容
        StringBuilder buf = new StringBuilder();
        // 当前段落从第几行开始、到第几行结束
        int start = -1, end = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.matches("#{1,6}\\s+.*")) {
                // Markdown 标题 = 新主题：先把前面攒的存掉，再换标题
                flush(segs, buf, start, end, heading, chunkChars);
                heading = line;
                start = -1;
                continue;
            }
            if (line.isBlank()) {
                // 空行 = 段落边界：把前面攒的存成一个段落
                flush(segs, buf, start, end, heading, chunkChars);
                start = -1;
                continue;
            }
            // 第一次遇到有内容的行，记下段落起点
            if (start < 0) start = i;
            // 每读一行都更新段落终点
            end = i;
            // 行与行之间补一个换行，保持原文的样子
            if (buf.length() > 0) buf.append('\n');
            buf.append(line);
            if (buf.length() >= chunkChars) {
                // 这一段太长了，先存掉，免得一块过大
                flush(segs, buf, start, end, heading, chunkChars);
                start = -1;
            }
        }
        // 文件最后剩下的一段也别忘了存
        flush(segs, buf, start, end, heading, chunkChars);
        return segs;
    }

    private static void flush(List<Seg> segs, StringBuilder buf, int start, int end,
                              String heading, int chunkChars) {
        // 取出攒好的文字，去掉首尾空白
        String text = buf.toString().strip();
        // 不管怎样先清空，下一次从头攒
        buf.setLength(0);
        // 还没开始攒，或者只有几个没用的符号（比如 "---"），直接丢掉
        if (start < 0 || text.length() < 4) return;
        if (text.length() > chunkChars) {
            // 太长就按句子再切小一点
            for (String piece : sentenceSplit(text, chunkChars)) {
                // 每小块的起止行号都沿用整个段落的
                segs.add(new Seg(piece, start, end, heading));
            }
        } else {
            // 长度合适，整段作为一段
            segs.add(new Seg(text, start, end, heading));
        }
    }

    /** 按句末标点切（保留标点在句尾），单句超长时允许超出 chunkChars */
    private static List<String> sentenceSplit(String text, int chunkChars) {
        // 装切出来的小段
        List<String> pieces = new ArrayList<>();
        // b 用来攒当前这一小段
        StringBuilder b = new StringBuilder();
        // 在句末标点后面切开，标点还留在句尾
        for (String s : text.split("(?<=[。！？；!?;])")) {
            if (b.length() > 0 && b.length() + s.length() > chunkChars) {
                // 再加这一句就超长了，先把已经攒的存起来
                pieces.add(b.toString());
                b.setLength(0);
            }
            b.append(s);
        }
        // 最后剩下的一小段也要存
        if (b.length() > 0) pieces.add(b.toString());
        return pieces;
    }
}
