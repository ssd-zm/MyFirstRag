package com.example.rag.rag;

import com.example.rag.store.Chunk;
import com.example.rag.store.VectorStore;

import java.util.List;

public final class PromptBuilder {

    public String build(String question, List<VectorStore.Hit> hits) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Chunk chunk = hits.get(i).chunk();
            context.append('[').append(i + 1).append("] ")
                    .append(chunk.source()).append(':').append(chunk.lineFrom()).append('\n')
                    .append(chunk.text()).append("\n\n");
        }
        return """
                只能依据 <资料> 中的内容回答问题。资料里没有的内容，直接回答"资料中未提及"，不要推测。
                回答控制在 6 行以内，并在最后单独一行给出引用，格式：引用：[序号] 文件名:起始行
                <资料>
                %s
                </资料>
                问题：%s
                """.formatted(context, question);
    }
}
