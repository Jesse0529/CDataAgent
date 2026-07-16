package com.AIBI.agent.assembler;

import java.util.regex.Pattern;

/** 普通对话展示格式边界：保留受控 Markdown，排除项目不支持的图谱 DSL。 */
public final class OutputFormatPolicy {

    private static final Pattern DIAGRAM_FENCE = Pattern.compile(
            "(?is)```\\s*(?:mermaid|plantuml|puml|dot|graphviz)\\b.*?```");

    private static final Pattern MERMAID_DIRECTIVE_BLOCK = Pattern.compile(
            "(?ims)^\\s*(?:(?:graph|flowchart)\\s+(?:TD|TB|BT|RL|LR)\\b|sequenceDiagram\\b|"
                    + "classDiagram\\b|stateDiagram(?:-v2)?\\b|erDiagram\\b|mindmap\\b|journey\\b|gantt\\b).*?(?=\\R\\s*\\R|\\z)");

    private OutputFormatPolicy() {
    }

    public static Result normalizeConversationMarkdown(String text) {
        String source = text == null ? "" : text;
        String normalized = DIAGRAM_FENCE.matcher(source).replaceAll(placeholder());
        normalized = MERMAID_DIRECTIVE_BLOCK.matcher(normalized).replaceAll(placeholder());
        return new Result(normalized, !source.equals(normalized));
    }

    private static String placeholder() {
        return "（关系图谱和流程图不支持直接展示，已转换为文本说明；如需分析关系，请使用表格或要点描述。）";
    }

    public record Result(String text, boolean diagramRemoved) {
    }
}
