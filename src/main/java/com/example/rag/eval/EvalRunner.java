package com.example.rag.eval;

import com.example.rag.llm.EmbeddingClient;
import com.example.rag.rag.Retriever;
import com.example.rag.store.Scored;
import com.example.rag.store.VectorStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 离线跑测评集。只算检索指标，不调用对话模型：又快又便宜，而且同一份数据每次结果都一样。
 * <p>
 * 测评集格式（Tab 分隔，一行一题，以 # 开头的是注释）：
 *   问题 <Tab> 期望命中的文件名[,另一个文件名]
 * 第二列写 "-" 表示这题笔记里本来就没有答案，用来检验门槛是否能把无关内容挡在外面。
 */
public final class EvalRunner {

    // 一条测试题：问题 + 期望命中的文件名列表；列表为空表示负样本
    public record Question(String text, List<String> expectedFiles) {
        // 负样本：笔记里不该有答案，理想情况是检索分数很低
        public boolean isNegative() {
            return expectedFiles.isEmpty();
        }
    }

    // 跑完一题的结果：命中的资料、期望文件排第几（0 表示没进 topK）、最高分
    public record Outcome(Question question, List<Scored> hits, int rank, double topScore) {
        // 正样本只要期望文件出现在 topK 里就算命中，不要求排第一
        public boolean hit() {
            return rank > 0;
        }

        // 负样本是否被错误地当成"有答案"：最高分不低于门槛，ask 就会硬答
        public boolean negativeLeaked(double minScore) {
            return question.isNegative() && topScore >= minScore;
        }

        // 命中但没排第一，属于"能答但排序偏后"，是 rerank 的目标场景
        public boolean lateRank() {
            return hit() && rank > 1;
        }
    }

    /**
     * 汇总指标。分数类字段用 NaN 表示"这一类题不存在"，避免 0 被误读成真实分数。
     * recallPercent 用 0~100，和打印出来的口径一致。
     */
    public record Summary(
            int posTotal, int posHit, int negTotal, int negRejected,
            double recallPercent, double mrr,
            double posMin, double posMedian, double posMax,
            double negMin, double negMedian, double negMax,
            double suggestLow, double suggestHigh, boolean posNegOverlap,
            long tokens) {}

    private final EmbeddingClient embedder;
    private final VectorStore store;

    public EvalRunner(EmbeddingClient embedder, VectorStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    // 读测评集文件，空行和 # 开头的行直接跳过
    public static List<Question> load(Path file) throws IOException {
        List<Question> questions = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // 必须用 Tab 分隔，用空格分隔的话问题里带空格就会把列切错
            String[] cols = line.split("\t");
            if (cols.length < 2) {
                throw new IllegalArgumentException(file.getFileName() + " 第 " + (i + 1)
                        + " 行格式不对，需要用 Tab 分隔两列: 问题<Tab>期望文件");
            }
            String text = cols[0].strip();
            String expected = cols[1].strip();
            // "-" 表示负样本；多个期望文件用逗号或分号隔开
            List<String> files = expected.isEmpty() || expected.equals("-")
                    ? List.of()
                    : Arrays.stream(expected.split("[,;]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            questions.add(new Question(text, files));
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("测评集是空的: " + file.toAbsolutePath());
        }
        return questions;
    }

    // 逐题检索，记录期望文件排在第几
    public List<Outcome> run(List<Question> questions, int topK) {
        Retriever retriever = new Retriever(embedder, store);
        List<Outcome> outcomes = new ArrayList<>(questions.size());
        for (Question question : questions) {
            List<Scored> hits = retriever.retrieve(question.text(), topK);

            // 找期望文件第一次出现的位置，下标从 0 开始，排名要加 1
            int rank = 0;
            for (int i = 0; i < hits.size(); i++) {
                String source = hits.get(i).chunk().sourcePath();
                for (String expected : question.expectedFiles()) {
                    // 带上切片行范围，标注了行号就要求那一行真的落在切片里
                    if (matches(expected, source, hits.get(i).chunk().lineFrom(), hits.get(i).chunk().lineTo())) {
                        rank = i + 1;
                        break;
                    }
                }
                if (rank > 0) break;
            }
            double topScore = hits.isEmpty() ? 0 : hits.get(0).score();
            outcomes.add(new Outcome(question, hits, rank, topScore));
        }
        return outcomes;
    }

    // 汇总指标，控制台打印和落盘报告共用这一份计算，避免两处口径不一致
    public Summary summarize(List<Outcome> outcomes, double minScore) {
        int posTotal = 0, posHit = 0, negTotal = 0, negRejected = 0;
        double mrrSum = 0;
        List<Double> posScores = new ArrayList<>();
        List<Double> negScores = new ArrayList<>();

        for (Outcome o : outcomes) {
            if (o.question().isNegative()) {
                negTotal++;
                negScores.add(o.topScore());
                // 负样本的"正确"就是最高分低于门槛，也就是 ask 会回答"资料中未提及"
                if (o.topScore() < minScore) negRejected++;
            } else {
                posTotal++;
                posScores.add(o.topScore());
                if (o.hit()) {
                    posHit++;
                    // MRR 用排名的倒数：第 1 名得 1 分，第 2 名得 0.5 分，越靠前越高
                    mrrSum += 1.0 / o.rank();
                }
            }
        }

        double posMin = Double.NaN, posMid = Double.NaN, posMax = Double.NaN;
        if (!posScores.isEmpty()) {
            posMin = min(posScores);
            posMid = median(posScores);
            posMax = max(posScores);
        }
        double negMin = Double.NaN, negMid = Double.NaN, negMax = Double.NaN;
        if (!negScores.isEmpty()) {
            negMin = min(negScores);
            negMid = median(negScores);
            negMax = max(negScores);
        }

        // 用两堆分数的分界推荐一个阈值：高于所有负样本、低于所有正样本才分得开
        double suggestLow = Double.NaN, suggestHigh = Double.NaN;
        boolean overlap = false;
        if (!posScores.isEmpty() && !negScores.isEmpty()) {
            if (max(negScores) < min(posScores)) {
                suggestLow = max(negScores);
                suggestHigh = min(posScores);
            } else {
                overlap = true;
            }
        }

        return new Summary(posTotal, posHit, negTotal, negRejected,
                posTotal == 0 ? 0 : posHit * 100.0 / posTotal,
                posTotal == 0 ? 0 : mrrSum / posTotal,
                posMin, posMid, posMax,
                negMin, negMid, negMax,
                suggestLow, suggestHigh, overlap,
                embedder.totalTokens());
    }

    // 把明细和汇总打到控制台
    public void printReport(List<Outcome> outcomes, int topK, double minScore) {
        Summary s = summarize(outcomes, minScore);

        System.out.printf("=== 测评集 %d 题，topK=%d，当前门槛 minScore=%.2f ===%n",
                outcomes.size(), topK, minScore);
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome o = outcomes.get(i);
            Question q = o.question();
            if (q.isNegative()) {
                boolean rejected = o.topScore() < minScore;
                System.out.printf("  #%-2d %s  top=%.4f  need=-（负样本）  %s%n",
                        i + 1, rejected ? "PASS" : "FAIL", o.topScore(), q.text());
            } else {
                System.out.printf("  #%-2d %s  top=%.4f  rank=%-2s need=%s  %s%n",
                        i + 1, o.hit() ? "PASS" : "FAIL", o.topScore(),
                        o.hit() ? String.valueOf(o.rank()) : "-",
                        String.join(",", q.expectedFiles()), q.text());
            }
        }

        System.out.println();
        if (s.posTotal() > 0) {
            System.out.printf("正样本 Recall@%d = %d/%d = %.1f%%%n",
                    topK, s.posHit(), s.posTotal(), s.recallPercent());
            System.out.printf("正样本 MRR      = %.2f（只看排序质量，1.0 表示每题都排第一）%n", s.mrr());
        }
        if (s.negTotal() > 0) {
            System.out.printf("负样本拒答率    = %d/%d（门槛 %.2f 下，越高说明越少硬答）%n",
                    s.negRejected(), s.negTotal(), minScore);
        }
        if (!Double.isNaN(s.posMin())) {
            System.out.printf("正样本 top1 分数: 最低 %.3f / 中位 %.3f / 最高 %.3f%n",
                    s.posMin(), s.posMedian(), s.posMax());
        }
        if (!Double.isNaN(s.negMin())) {
            System.out.printf("负样本 top1 分数: 最低 %.3f / 中位 %.3f / 最高 %.3f%n",
                    s.negMin(), s.negMedian(), s.negMax());
        }
        if (!Double.isNaN(s.suggestLow())) {
            System.out.printf("=> 建议阈值落在 (%.3f, %.3f) 之间%n", s.suggestLow(), s.suggestHigh());
        } else if (s.posNegOverlap()) {
            System.out.println("=> 正负样本分数有重叠，单靠阈值分不开，需要混合检索或 rerank 改善排序");
        }
        System.out.printf("embedding tokens 合计 = %d（只算向量，没调对话模型）%n", s.tokens());
        System.out.println();
        System.out.println("怎么看 FAIL：");
        System.out.println("  top1 分数高但 rank=-：内容是有的，是切分或排序的问题");
        System.out.println("  top1 分数低：笔记里确实没有，或者问法和原文差太远");
        System.out.println("  负样本 FAIL：阈值偏低，把门槛往上调一点再跑一次");
    }

    // 文件名对比：忽略目录前缀，允许只写文件名，也允许写成 notes/xx.md。
    // 还支持 "文件:行号"（例如 rag-notes.md:13），表示答案所在的那一行必须落在命中切片里。
    private static boolean matches(String expected, String sourcePath, int lineFrom, int lineTo) {
        String file = expected;
        int line = -1;
        // 用最后一个冒号切出文件名和行号，避免文件名里恰好带冒号
        int sep = expected.lastIndexOf(':');
        if (sep > 0) {
            String tail = expected.substring(sep + 1);
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                file = expected.substring(0, sep);
                line = Integer.parseInt(tail);
            }
        }
        String normalized = sourcePath.replace('\\', '/');
        String fileName = Path.of(normalized).getFileName().toString();
        boolean fileOk = fileName.equalsIgnoreCase(file)
                || normalized.equalsIgnoreCase(file)
                || normalized.endsWith("/" + file);
        if (!fileOk) return false;
        // 没写行号就只比文件；写了行号就要求这一行落在 [lineFrom, lineTo] 内
        return line < 0 || (line >= lineFrom && line <= lineTo);
    }

    private static double min(List<Double> values) {
        return Collections.min(values);
    }

    private static double max(List<Double> values) {
        return Collections.max(values);
    }

    // 中位数：排序后取中间那个；偶数个就取中间两个的平均
    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
