package com.example.rag.eval;

import com.example.rag.store.Scored;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次评测落到 eval/runs/ 下，并在下一次运行时自动和上次比对。
 * <p>
 * 每次产出两个文件：
 *   1. yyyyMMdd-HHmmss.md：给人看的归档，含参数快照、指标、失败专区、全量明细
 *   2. latest.json：给机器看的基线，下次运行时读它做 diff，然后被本次覆盖
 * <p>
 * 为什么要记参数快照：Recall 掉了到底是切分改了、还是 topK 改了、还是模型换了，
 * 只看一个数字永远说不清。快照里还记了索引文件的修改时间，用来识别
 * "参数改了但忘了重跑 index"这种会让比对彻底失效的情况。
 */
public final class EvalReport {

    // 本次评测真正生效的参数。改切片类参数后必须重跑 index，否则拿旧索引测评，结果不可比
    public record Snapshot(
            int chunks, int chunkChars, int overlapChars,
            int topK, double minScore, String embedModel,
            long indexModifiedMillis, long indexSizeBytes) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LATEST = "latest.json";

    public static String now() {
        return LocalDateTime.now().format(HUMAN);
    }

    /**
     * 先对比上一次，再写本次。顺序不能反：write 会覆盖 latest.json，先写就丢了基线。
     */
    public static void printDiff(Path runsDir, EvalRunner.Summary summary,
                                 Snapshot snap, List<EvalRunner.Outcome> outcomes) {
        Path latest = runsDir.resolve(LATEST);
        if (!Files.exists(latest)) {
            System.out.println();
            System.out.println("=== 与上次对比 ===");
            System.out.println("（还没有历史记录，本次结果作为基线，已保存到 eval/runs/）");
            return;
        }
        JsonNode prev;
        try {
            prev = MAPPER.readTree(Files.readString(latest, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("=== 与上次对比 ===");
            System.out.println("（历史记录读不出来，跳过对比: " + e.getMessage() + "）");
            return;
        }

        System.out.println();
        System.out.println("=== 与上次对比（上次 " + prev.path("time").asText("未知时间") + "）===");
        printParamDiff(prev.path("params"), snap);

        // 指标变化：只在两边都有正样本时才谈 Recall
        JsonNode pm = prev.path("metrics");
        if (summary.posTotal() > 0 && pm.has("recallPercent")) {
            double oldRecall = pm.path("recallPercent").asDouble();
            double newRecall = round(summary.recallPercent());
            System.out.printf("Recall@%d  %.1f%% -> %.1f%%  (%s)%n",
                    snap.topK(), oldRecall, newRecall, signed(newRecall - oldRecall));
            double oldMrr = pm.path("mrr").asDouble();
            double newMrr = round(summary.mrr());
            System.out.printf("MRR         %.2f -> %.2f  (%s)%n",
                    oldMrr, newMrr, signed(newMrr - oldMrr));
        }
        if (summary.negTotal() > 0 && pm.has("negRejected")) {
            System.out.printf("负样本拒答  %d/%d -> %d/%d%n",
                    pm.path("negRejected").asInt(), pm.path("negTotal").asInt(),
                    summary.negRejected(), summary.negTotal());
        }

        // 题级变化：用问题文本匹配，题号可能因为插题而错位，不能拿序号当主键
        Map<String, Boolean> oldOk = new LinkedHashMap<>();
        for (JsonNode c : prev.path("cases")) {
            oldOk.put(c.path("question").asText(), c.path("ok").asBoolean());
        }
        List<String> fixed = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        List<String> stillBad = new ArrayList<>();
        for (EvalRunner.Outcome o : outcomes) {
            String q = o.question().text();
            boolean ok = isOk(o, snap.minScore());
            if (!oldOk.containsKey(q)) continue;
            boolean was = oldOk.get(q);
            if (!was && ok) fixed.add(q);
            else if (was && !ok) broken.add(q);
            else if (!was) stillBad.add(q);
        }

        if (!fixed.isEmpty()) System.out.println("修复      " + join(fixed));
        if (!broken.isEmpty()) System.out.println("新增失败  " + join(broken));
        if (!stillBad.isEmpty()) System.out.println("仍失败    " + join(stillBad));
        if (fixed.isEmpty() && broken.isEmpty() && stillBad.isEmpty()) {
            System.out.println("题级结果与上次完全一致");
        }
    }

    private static void printParamDiff(JsonNode old, Snapshot snap) {
        List<String> changed = new ArrayList<>();
        diffInt(changed, "chunkChars", old, snap.chunkChars());
        diffInt(changed, "overlapChars", old, snap.overlapChars());
        diffInt(changed, "topK", old, snap.topK());
        diffString(changed, "embedModel", old, snap.embedModel());
        if (Math.abs(old.path("minScore").asDouble(-1) - snap.minScore()) > 1e-6) {
            changed.add("minScore " + old.path("minScore").asDouble() + " -> " + snap.minScore());
        }
        if (changed.isEmpty()) {
            System.out.println("参数      未变化");
        } else {
            System.out.println("参数变化  " + String.join(", ", changed));
        }

        // 影响索引内容的参数变了，但索引文件没重新生成：两次结果压根不可比
        long oldMtime = old.path("indexModifiedMillis").asLong(-1);
        int oldChunkChars = old.path("chunkChars").asInt(-1);
        boolean indexAffecting = oldChunkChars > 0
                && (oldChunkChars != snap.chunkChars()
                || old.path("overlapChars").asInt(-1) != snap.overlapChars()
                || !old.path("embedModel").asText("").equals(snap.embedModel()));
        if (indexAffecting && oldMtime > 0 && oldMtime == snap.indexModifiedMillis()) {
            System.out.println("!! 警告: 切片参数或 embedding 模型变了，但索引文件没有重建。");
            System.out.println("   本次跑的还是旧索引，两次结果不可比，请先执行 index 再跑 eval。");
        }
    }

    /**
     * 写本次结果：一个归档 md + 覆盖 latest.json。
     *
     * @return 生成的 md 路径
     */
    public static Path write(Path runsDir, EvalRunner.Summary summary,
                             Snapshot snap, List<EvalRunner.Outcome> outcomes) {
        String time = now();
        try {
            Files.createDirectories(runsDir);
            Path md = runsDir.resolve(LocalDateTime.now().format(STAMP) + ".md");
            Files.writeString(md, buildMarkdown(time, summary, snap, outcomes), StandardCharsets.UTF_8);
            Files.writeString(runsDir.resolve(LATEST),
                    buildJson(time, summary, snap, outcomes).toPrettyString(), StandardCharsets.UTF_8);
            return md;
        } catch (Exception e) {
            System.out.println("（保存评测报告失败: " + e.getMessage() + "）");
            return null;
        }
    }

    // ---------- Markdown ----------

    private static String buildMarkdown(String time, EvalRunner.Summary s,
                                        Snapshot snap, List<EvalRunner.Outcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 评测报告 ").append(time).append("\n\n");

        sb.append("## 参数快照\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| chunkChars | ").append(snap.chunkChars()).append(" |\n");
        sb.append("| overlapChars | ").append(snap.overlapChars()).append(" |\n");
        sb.append("| topK | ").append(snap.topK()).append(" |\n");
        sb.append("| minScore | ").append(String.format("%.2f", snap.minScore())).append(" |\n");
        sb.append("| embedModel | ").append(snap.embedModel()).append(" |\n");
        sb.append("| 索引 chunk 数 | ").append(snap.chunks()).append(" |\n");
        sb.append("| 索引修改时间 | ")
                .append(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(snap.indexModifiedMillis()),
                        java.time.ZoneId.systemDefault()).format(HUMAN))
                .append(" |\n\n");
        sb.append("> 改切片参数或 embedding 模型后必须重跑 `index`，否则本次结果和上次不可比。\n\n");

        sb.append("## 汇总\n\n");
        if (s.posTotal() > 0) {
            sb.append("- 正样本 ").append(s.posTotal()).append(" 题，Recall@").append(snap.topK())
                    .append(" = ").append(String.format("%.1f%%", s.recallPercent()))
                    .append("（").append(s.posHit()).append("/").append(s.posTotal()).append("）\n");
            sb.append("- MRR = ").append(String.format("%.2f", s.mrr())).append("\n");
        }
        if (s.negTotal() > 0) {
            sb.append("- 负样本 ").append(s.negTotal()).append(" 题，拒答率 = ")
                    .append(s.negRejected()).append("/").append(s.negTotal())
                    .append("（门槛 ").append(String.format("%.2f", snap.minScore())).append("）\n");
        }
        if (!Double.isNaN(s.posMin())) {
            sb.append("- 正样本 top1 分数：最低 ").append(String.format("%.3f", s.posMin()))
                    .append(" / 中位 ").append(String.format("%.3f", s.posMedian()))
                    .append(" / 最高 ").append(String.format("%.3f", s.posMax())).append("\n");
        }
        if (!Double.isNaN(s.negMin())) {
            sb.append("- 负样本 top1 分数：最低 ").append(String.format("%.3f", s.negMin()))
                    .append(" / 中位 ").append(String.format("%.3f", s.negMedian()))
                    .append(" / 最高 ").append(String.format("%.3f", s.negMax())).append("\n");
        }
        if (!Double.isNaN(s.suggestLow())) {
            sb.append("- 建议阈值落在 (").append(String.format("%.3f", s.suggestLow()))
                    .append(", ").append(String.format("%.3f", s.suggestHigh())).append(") 之间\n");
        } else if (s.posNegOverlap()) {
            sb.append("- 正负样本分数有重叠，单靠阈值分不开\n");
        }
        sb.append("- embedding tokens = ").append(s.tokens()).append("\n\n");

        // 失败专区：这才是下次优化的输入，所以放全量明细前面
        sb.append("## 失败与非预期\n\n");
        List<EvalRunner.Outcome> missed = new ArrayList<>();
        List<EvalRunner.Outcome> leaked = new ArrayList<>();
        List<EvalRunner.Outcome> late = new ArrayList<>();
        for (EvalRunner.Outcome o : outcomes) {
            if (o.question().isNegative()) {
                if (o.negativeLeaked(snap.minScore())) leaked.add(o);
            } else if (!o.hit()) {
                missed.add(o);
            } else if (o.lateRank()) {
                late.add(o);
            }
        }
        if (missed.isEmpty() && leaked.isEmpty() && late.isEmpty()) {
            sb.append("本次没有失败项。\n\n");
        }
        if (!missed.isEmpty()) {
            sb.append("### 正样本未命中（").append(missed.size()).append(" 题）\n\n");
            sb.append("| # | 问题 | 期望命中 | top1 实际来源 | 分数 | 判读 |\n|---|---|---|---|---|---|\n");
            for (EvalRunner.Outcome o : missed) {
                sb.append("| ").append(indexOf(outcomes, o)).append(" | ")
                        .append(esc(o.question().text())).append(" | ")
                        .append(esc(String.join(",", o.question().expectedFiles()))).append(" | ")
                        .append(esc(top1Source(o))).append(" | ")
                        .append(String.format("%.4f", o.topScore())).append(" | ")
                        .append(o.topScore() >= snap.minScore()
                                ? "检索到的是别的片段，优先调切分/topK"
                                : "分数低于门槛，可能笔记里真没有，或问法差太远")
                        .append(" |\n");
            }
            sb.append("\n");
        }
        if (!leaked.isEmpty()) {
            sb.append("### 负样本被误判为可答（").append(leaked.size()).append(" 题）\n\n");
            sb.append("这些题笔记里本来没有答案，但最高分超过了门槛，ask 会硬答。\n\n");
            sb.append("| # | 问题 | top1 来源 | 分数 | 门槛 |\n|---|---|---|---|---|\n");
            for (EvalRunner.Outcome o : leaked) {
                sb.append("| ").append(indexOf(outcomes, o)).append(" | ")
                        .append(esc(o.question().text())).append(" | ")
                        .append(esc(top1Source(o))).append(" | ")
                        .append(String.format("%.4f", o.topScore())).append(" | ")
                        .append(String.format("%.2f", snap.minScore())).append(" |\n");
            }
            sb.append("\n");
        }
        if (!late.isEmpty()) {
            sb.append("### 排序偏后（命中但非第一，").append(late.size()).append(" 题）\n\n");
            sb.append("检索到了但排在后面，是 rerank 的目标场景。\n\n");
            sb.append("| # | 问题 | 期望命中 | 实际排名 | 分数 |\n|---|---|---|---|---|\n");
            for (EvalRunner.Outcome o : late) {
                sb.append("| ").append(indexOf(outcomes, o)).append(" | ")
                        .append(esc(o.question().text())).append(" | ")
                        .append(esc(String.join(",", o.question().expectedFiles()))).append(" | ")
                        .append(o.rank()).append(" | ")
                        .append(String.format("%.4f", o.topScore())).append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 全量明细\n\n");
        sb.append("| # | 结果 | 类型 | 问题 | 期望 | rank | 分数 | top1 来源 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < outcomes.size(); i++) {
            EvalRunner.Outcome o = outcomes.get(i);
            boolean ok = isOk(o, snap.minScore());
            sb.append("| ").append(i + 1).append(" | ").append(ok ? "PASS" : "FAIL").append(" | ")
                    .append(o.question().isNegative() ? "负样本" : "正样本").append(" | ")
                    .append(esc(o.question().text())).append(" | ")
                    .append(esc(o.question().isNegative() ? "-" : String.join(",", o.question().expectedFiles()))).append(" | ")
                    .append(o.hit() ? String.valueOf(o.rank()) : "-").append(" | ")
                    .append(String.format("%.4f", o.topScore())).append(" | ")
                    .append(esc(top1Source(o))).append(" |\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    // ---------- JSON（供下次 diff） ----------

    private static ObjectNode buildJson(String time, EvalRunner.Summary s,
                                       Snapshot snap, List<EvalRunner.Outcome> outcomes) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("time", time);

        ObjectNode params = root.putObject("params");
        params.put("chunks", snap.chunks());
        params.put("chunkChars", snap.chunkChars());
        params.put("overlapChars", snap.overlapChars());
        params.put("topK", snap.topK());
        params.put("minScore", snap.minScore());
        params.put("embedModel", snap.embedModel());
        params.put("indexModifiedMillis", snap.indexModifiedMillis());
        params.put("indexSizeBytes", snap.indexSizeBytes());

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("posTotal", s.posTotal());
        metrics.put("posHit", s.posHit());
        metrics.put("recallPercent", round(s.recallPercent()));
        metrics.put("mrr", round(s.mrr()));
        metrics.put("negTotal", s.negTotal());
        metrics.put("negRejected", s.negRejected());

        ArrayNode cases = root.putArray("cases");
        for (EvalRunner.Outcome o : outcomes) {
            ObjectNode c = cases.addObject();
            c.put("question", o.question().text());
            c.put("negative", o.question().isNegative());
            c.put("expected", String.join(",", o.question().expectedFiles()));
            c.put("rank", o.rank());
            c.put("topScore", round4(o.topScore()));
            c.put("ok", isOk(o, snap.minScore()));
            c.put("top1", top1Source(o));
        }
        return root;
    }

    // ---------- 小工具 ----------

    // 正样本看是否命中；负样本看最高分是否低于门槛
    private static boolean isOk(EvalRunner.Outcome o, double minScore) {
        return o.question().isNegative() ? o.topScore() < minScore : o.hit();
    }

    private static String top1Source(EvalRunner.Outcome o) {
        if (o.hits().isEmpty()) return "（无命中）";
        Scored top = o.hits().get(0);
        String name = Path.of(top.chunk().sourcePath()).getFileName().toString();
        return name + ":" + top.chunk().lineFrom() + "-" + top.chunk().lineTo();
    }

    private static int indexOf(List<EvalRunner.Outcome> outcomes, EvalRunner.Outcome target) {
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) == target) return i + 1;
        }
        return 0;
    }

    // Markdown 表格里竖线会把列切断，换成转义写法
    private static String esc(String text) {
        return text.replace("|", "\\|");
    }

    private static String join(List<String> items) {
        return String.join("；", items);
    }

    private static String signed(double delta) {
        return (delta >= 0 ? "+" : "") + String.format("%.1f", delta);
    }

    private static void diffInt(List<String> out, String name, JsonNode old, int now) {
        int was = old.path(name).asInt(Integer.MIN_VALUE);
        if (was != Integer.MIN_VALUE && was != now) {
            out.add(name + " " + was + " -> " + now);
        }
    }

    private static void diffString(List<String> out, String name, JsonNode old, String now) {
        String was = old.path(name).asText("");
        if (!was.isEmpty() && !was.equals(now)) {
            out.add(name + " " + was + " -> " + now);
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
