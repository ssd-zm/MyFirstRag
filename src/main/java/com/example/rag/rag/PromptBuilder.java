package com.example.rag.rag;

import com.example.rag.store.Scored;

import java.nio.file.Path;
import java.util.List;

/**
 * 拼 prompt。system 里放硬约束（防幻觉 + 引用格式），user 里放资料和问题。
 * 每条资料前带【来源】，模型才能在答案末尾给出 [文件名:行号] 引用。
 */
public final class PromptBuilder {

    // 这个类全是静态方法，不需要创建对象，所以把构造函数藏起来
    private PromptBuilder() {}

    /** 返回 [systemPrompt, userPrompt] */
    public static String[] build(List<Scored> hits, String question) {
        // 把所有命中的资料拼成一大段文字
        StringBuilder sb = new StringBuilder();
        for (Scored s : hits) {
            // 取出这一条切片
            var c = s.chunk();
            // 只取文件名，不用把整条路径都塞给模型
            String name = Path.of(c.sourcePath()).getFileName().toString();
            // 每条资料前面写上来源，模型才好照着写引用
            sb.append("【来源】").append(name).append(":").append(c.lineFrom()).append("行\n")
              .append(c.text()).append("\n\n");
        }

        // system 提示：把规矩说清楚，防止模型瞎编
        String system = """
                你是一个严格的资料问答助手，必须遵守:
                1. 只依据 <资料> 中的内容回答，不要使用资料之外的知识。
                2. 资料中没有的信息，只输出一句"资料中未提及"，不要补充任何其他内容。
                3. 只有在参考答案时才在末尾列出引用，每条格式: [文件名:起始行号]；
                   回答"资料中未提及"时不要附任何引用，否则等于一边否认依据一边给出依据。
                """;

        // user 内容：先给资料，再给问题
        String user = """
                <资料>
                %s</资料>

                问题:%s
                """.formatted(sb.toString().strip(), question);

        // 用数组把两段都返回，调用方用 [0]、[1] 取
        return new String[]{system, user};
    }
}
