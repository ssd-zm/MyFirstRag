package com.example.rag.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * 按段落切块，保留 Markdown 标题与起始行号；超长段落按中文句末标点再切一刀。
 * 相邻块之间用「上一块尾部 N 个字符」做重叠，避免答案刚好落在边界上被切断。
 */
public final class TextSplitter {

    public record Piece(String text, int lineFrom) {
    }

    private static final char[] SENTENCE_ENDS = {'。', '！', '？', '；', '\n'};

    private final int chunkChars;
    private final int overlapChars;

    public TextSplitter(int chunkChars, int overlapChars) {
        this.chunkChars = Math.max(100, chunkChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, this.chunkChars / 2));
    }

    public List<Piece> split(String content) {
        List<Piece> blocks = toBlocks(content);
        List<Piece> pieces = new ArrayList<>();
        int index = 0;
        String carryText = "";
        int carryLine = 0;

        while (index < blocks.size()) {
            StringBuilder buffer = new StringBuilder();
            int startLine = -1;

            // 只有重叠部分加上下一段还放得下时才带上，保证每轮至少吃掉一个段落，不会死循环
            if (!carryText.isEmpty()
                    && carryText.length() + blocks.get(index).text().length() + 1 <= chunkChars) {
                buffer.append(carryText);
                startLine = carryLine;
            }

            int cursor = index;
            while (cursor < blocks.size()) {
                Piece block = blocks.get(cursor);
                if (buffer.length() > 0 && buffer.length() + block.text().length() > chunkChars) {
                    break;
                }
                if (startLine < 0) {
                    startLine = block.lineFrom();
                }
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(block.text());
                cursor++;
            }

            if (startLine < 0) {
                startLine = blocks.get(index).lineFrom();
            }
            String text = buffer.toString().strip();
            if (!text.isEmpty()) {
                pieces.add(new Piece(text, startLine));
            }

            carryText = overlapOf(buffer);
            int keep = buffer.length() - carryText.length();
            carryLine = startLine + countNewlines(buffer.substring(0, Math.max(0, keep)));
            index = Math.max(cursor, index + 1);
        }
        return pieces;
    }

    /** 先按标题/空行切成段落，再把超长段落单独切开。 */
    private List<Piece> toBlocks(String content) {
        List<Piece> blocks = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        StringBuilder current = new StringBuilder();
        int currentStart = 1;
        boolean inFence = false;
        boolean justAfterHeading = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].replace("\r", "");
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
            }
            boolean isHeading = !inFence && line.startsWith("#");

            if (isHeading) {
                flush(blocks, current, currentStart);
                currentStart = lineNo;
                current.append(line.strip()).append('\n');
                justAfterHeading = true;
                continue;
            }
            if (line.isBlank()) {
                if (!justAfterHeading && !inFence) {
                    flush(blocks, current, currentStart);
                }
                justAfterHeading = false;
                continue;
            }
            justAfterHeading = false;
            if (current.length() == 0) {
                currentStart = lineNo;
            }
            current.append(line.strip()).append('\n');
        }
        flush(blocks, current, currentStart);

        List<Piece> result = new ArrayList<>(blocks.size());
        for (Piece block : blocks) {
            if (block.text().length() <= chunkChars) {
                result.add(block);
            } else {
                result.addAll(hardSplit(block));
            }
        }
        return result;
    }

    private static void flush(List<Piece> blocks, StringBuilder current, int startLine) {
        String text = current.toString().strip();
        if (!text.isEmpty()) {
            blocks.add(new Piece(text, startLine));
        }
        current.setLength(0);
    }

    private List<Piece> hardSplit(Piece block) {
        List<Piece> parts = new ArrayList<>();
        String text = block.text();
        int from = 0;
        while (from < text.length()) {
            int end = Math.min(text.length(), from + chunkChars);
            if (end < text.length()) {
                for (int k = end; k > from + chunkChars / 2; k--) {
                    if (isSentenceEnd(text.charAt(k - 1))) {
                        end = k;
                        break;
                    }
                }
            }
            String part = text.substring(from, end).strip();
            if (!part.isEmpty()) {
                parts.add(new Piece(part, block.lineFrom() + countNewlines(text.substring(0, from))));
            }
            from = end;
        }
        return parts;
    }

    private String overlapOf(CharSequence text) {
        if (overlapChars == 0 || text.length() <= overlapChars) {
            return "";
        }
        int from = text.length() - overlapChars;
        for (int k = from; k < text.length(); k++) {
            if (isSentenceEnd(text.charAt(k))) {
                from = k + 1;
                break;
            }
        }
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        return from >= text.length() ? "" : text.toString().substring(from);
    }

    private static boolean isSentenceEnd(char c) {
        for (char end : SENTENCE_ENDS) {
            if (c == end) {
                return true;
            }
        }
        return false;
    }

    private static int countNewlines(CharSequence text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
