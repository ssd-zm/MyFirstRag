package com.example.rag.rag;

import com.example.rag.store.Scored;

import java.nio.file.Path;
import java.util.List;

/**
 * 拼 prompt。system 里放硬约束（防幻觉 + 引用格式），user 里放资料和问题。
 * 每条资料前带【来源】，模型才能在答案末尾给出 [文件名:行号] 引用。
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    /** 返回 [systemPrompt, userPrompt] */
    public static String[] build(List<Scored> hits, String question) {
        StringBuilder sb = new StringBuilder();
        for (Scored s : hits) {
            var c = s.chunk();
            String name = Path.of(c.sourcePath()).getFileName().toString();
            sb.append("【来源】").append(name).append(":").append(c.lineFrom()).append("行\n")
              .append(c.text()).append("\n\n");
        }

        String system = """
                你是一个严格的资料问答助手，必须遵守:
                1. 只依据 <资料> 中的内容回答，不要使用资料之外的知识。
                2. 资料中没有的信息，直接回答"资料中未提及"，不要推测或编造。
                3. 回答末尾列出引用来源，每条格式: [文件名:起始行号]
                """;

        String user = """
                <资料>
                %s</资料>

                问题:%s
                """.formatted(sb.toString().strip(), question);

        return new String[]{system, user};
    }
}
