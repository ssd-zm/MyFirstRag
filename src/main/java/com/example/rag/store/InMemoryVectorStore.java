package com.example.rag.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存向量库：切片原文放 chunks.json，向量按 float32 顺序写进 vectors.bin。
 * 不引入数据库，重启时直接加载这两个文件。
 */
public final class InMemoryVectorStore implements VectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String META_FILE = "chunks.json";
    private static final String VECTOR_FILE = "vectors.bin";

    private final List<Chunk> chunks = new ArrayList<>();

    @Override
    public void addAll(List<Chunk> list) {
        chunks.addAll(list);
    }

    @Override
    public int size() {
        return chunks.size();
    }

    public int dimension() {
        return chunks.isEmpty() ? 0 : chunks.get(0).vector().length;
    }

    @Override
    public List<Hit> search(float[] queryVector, int topK) {
        List<Hit> hits = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            float[] vector = chunk.vector();
            if (vector == null) {
                throw new IllegalStateException("切片 " + chunk.id() + " 没有向量，索引还没建完");
            }
            if (vector.length != queryVector.length) {
                throw new IllegalArgumentException("向量维度不一致：索引 " + vector.length
                        + " 维，查询 " + queryVector.length + " 维。换过 embedding 模型就必须重建索引。");
            }
            hits.add(new Hit(chunk, dot(queryVector, vector)));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return new ArrayList<>(hits.subList(0, Math.min(topK, hits.size())));
    }

    public void save(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<Meta> metas = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            metas.add(new Meta(chunk.id(), chunk.source(), chunk.lineFrom(), chunk.text()));
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(META_FILE).toFile(), metas);

        int dimension = dimension();
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dir.resolve(VECTOR_FILE))))) {
            out.writeInt(chunks.size());
            out.writeInt(dimension);
            for (Chunk chunk : chunks) {
                for (float value : chunk.vector()) {
                    out.writeFloat(value);
                }
            }
        }
    }

    public static InMemoryVectorStore load(Path dir) throws IOException {
        Path metaPath = dir.resolve(META_FILE);
        Path vectorPath = dir.resolve(VECTOR_FILE);
        if (!Files.exists(metaPath) || !Files.exists(vectorPath)) {
            throw new IOException("索引文件不存在：" + dir.toAbsolutePath() + "，请先执行 index 命令");
        }
        List<Meta> metas = MAPPER.readValue(metaPath.toFile(), new TypeReference<List<Meta>>() {
        });

        InMemoryVectorStore store = new InMemoryVectorStore();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(vectorPath)))) {
            int count = in.readInt();
            int dimension = in.readInt();
            if (count != metas.size()) {
                throw new IOException("索引损坏：元数据 " + metas.size() + " 条，向量 " + count + " 条");
            }
            for (int i = 0; i < count; i++) {
                float[] vector = new float[dimension];
                for (int k = 0; k < dimension; k++) {
                    vector[k] = in.readFloat();
                }
                Meta meta = metas.get(i);
                store.chunks.add(new Chunk(meta.id(), meta.source(), meta.lineFrom(), meta.text(), vector));
            }
        }
        return store;
    }

    /** 向量在入库前已归一化，余弦相似度等价于点积。 */
    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    public record Meta(String id, String source, int lineFrom, String text) {
    }
}
