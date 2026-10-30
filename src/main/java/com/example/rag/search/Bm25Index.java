package com.example.rag.search;

import com.example.rag.store.Chunk;
import com.example.rag.store.Scored;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写 BM25 检索索引。
 * BM25 是"关键词打分"：问题里的词在切片中出现越多、这个词越稀有，分数越高。
 * 和向量检索互补——专有名词、类名、HTTP 头名这类精确词，向量容易糊，BM25 一抓一个准。
 * 只依赖文本，建索引和查询都不需要调 embedding 接口。
 */
public final class Bm25Index {

    // k1：词频饱和度。1.2 是经典默认值，不用调
    private static final double K1 = 1.2;
    // b：长度归一化强度。0 不惩罚长切片，1 完全按长度折算。0.75 是经典默认值
    private static final double B = 0.75;

    // 每个切片的 term -> 出现次数，下标和 chunks 一一对应
    private final List<Map<String, Integer>> docTerms = new ArrayList<>();
    // 切片本体，下标和 docTerms 一一对应
    private final List<Chunk> chunks = new ArrayList<>();
    // term -> 含有它的切片数量（文档频率 df），算 IDF 用
    private final Map<String, Integer> df = new HashMap<>();
    // 平均切片长度（按 term 总数算），做长度归一化用
    private double avgDl = 1.0;

    /** 建索引：分词、统计词频和文档频率。纯内存计算，毫秒级 */
    public void build(List<Chunk> chunkList) {
        for (Chunk chunk : chunkList) {
            // 建库和查询必须用同一个分词器，口径不一致 term 就对不上
            List<String> terms = TrigramTokenizer.tokenize(chunk.text());
            // 统计当前切片内每个 term 的出现次数（词频 tf）
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms) {
                tf.merge(term, 1, Integer::sum);
            }
            docTerms.add(tf);
            chunks.add(chunk);
            // 这个切片里出现过的每个 term，文档频率 +1（同一切片出现多次只算一个文档）
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        // 平均长度；索引为空时给 1，避免除 0
        avgDl = docTerms.isEmpty() ? 1.0
                : docTerms.stream()
                .mapToInt(m -> m.values().stream().mapToInt(Integer::intValue).sum())
                .average().orElse(1.0);
    }

    /** 检索：给问题打分，返回得分最高的前 topK 个切片 */
    public List<Scored> search(String query, int topK) {
        // 问题也走同一个分词器
        Map<String, Integer> queryTf = new HashMap<>();
        for (String term : TrigramTokenizer.tokenize(query)) {
            queryTf.merge(term, 1, Integer::sum);
        }

        int n = chunks.size();
        List<Scored> results = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            Map<String, Integer> tf = docTerms.get(d);
            // 当前切片长度 = 所有词频加起来
            int dl = tf.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (Map.Entry<String, Integer> e : queryTf.entrySet()) {
                String term = e.getKey();
                // 查询里的词不在语料里（比如 RDB），文档频率为 null，直接跳过
                Integer docFreq = df.get(term);
                if (docFreq == null) continue;
                // 这个词也不在当前切片里，贡献 0 分
                Integer f = tf.get(term);
                if (f == null) continue;
                // IDF：越稀有的词越值钱
                double idf = Math.log(1 + (n - docFreq + 0.5) / (docFreq + 0.5));
                // 词频部分：出现多次有加分，但被 k1 压着不会线性涨；除号里是长度归一化
                double tfPart = f * (K1 + 1)
                        / (f + K1 * (1 - B + B * dl / avgDl));
                score += idf * tfPart;
            }
            // 分数为 0 的切片不进结果，和向量检索不同——BM25 对"没关系"很干脆
            if (score > 0) {
                results.add(new Scored(chunks.get(d), score));
            }
        }
        // 分数从高到低，截取前 topK
        results.sort((a, b2) -> Double.compare(b2.score(), a.score()));
        return results.size() > topK ? new ArrayList<>(results.subList(0, topK)) : results;
    }
}