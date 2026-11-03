package com.example.rag.search;

import com.example.rag.store.Chunk;
import com.example.rag.store.Scored;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）融合：把多路检索的排名合成一个排名。
 * 核心思想：不看各路的绝对分数（向量和 BM25 量纲不一样，没法比），
 *          只看"某切片在这一路排第几"，越靠前加分越多，排得越靠后加分越少。
 * 公式：融合分 = Σ 1/(k + rank_i)，k=60 是经典默认值。
 *
 * 为什么融合比单路好：
 *   - 向量：擅长语义相似（"缓存击穿" 和 "热点key过期" 算一类）
 *   - BM25：擅长精确词匹配（"Retry-After" 就是 "Retry-After"，不会乱凑相似）
 *   - 融合：两路都排得好的切片自然冒到最前面
 */
public final class RrfFusion {

    // k 值：经典默认 60。它控制"排名靠前"的加分强度。
    // 60 是经验值，小数据集可以调小到 30，大数据集保持 60。
    private static final double K = 60.0;

    private RrfFusion() {}

    /**
     * 把多路检索结果融合成一个排名。
     * 输入是多路 List<Scored>，输出是融合后的 List<Scored>。
     * 融合后的"分数"不再是原始分数，而是 RRF 融合分，仅用于排序。
     *
     * @param rankedLists 多路排序好的结果（每路内部已按 score 降序）
     * @param topK        最终返回多少个
     */
    public static List<Scored> fuse(List<List<Scored>> rankedLists, int topK) {
        // 用 Map 累积融合分，key 是 Chunk.id（每个切片的唯一标识）
        // 不用原始 Chunk 对象做 key 是因为 record 的 equals/hashCode 会把 text 也算进去，
        // 而不同来源的同一个切片可能有不同的 text 版本
        Map<String, Double> fused = new HashMap<>();
        // 保留 Chunk 引用，最后组装结果时用
        Map<String, Chunk> chunkMap = new HashMap<>();

        for (List<Scored> ranked : rankedLists) {
            // 每路最多取前 20 个参与融合，rank > 20 时 1/(60+20)=0.0125，贡献已经很小
            int limit = Math.min(ranked.size(), 20);
            for (int i = 0; i < limit; i++) {
                Chunk chunk = ranked.get(i).chunk();
                String id = chunk.id();
                // rank 从 1 开始，i 从 0 开始，所以 rank = i + 1
                double score = 1.0 / (K + i + 1);
                fused.merge(id, score, Double::sum);
                chunkMap.put(id, chunk);
            }
        }

        // 按融合分从高到低排序，取前 topK
        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> new Scored(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }
}