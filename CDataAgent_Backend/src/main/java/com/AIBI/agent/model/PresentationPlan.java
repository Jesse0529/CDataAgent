package com.AIBI.agent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 展示计划 — 后端内部模型，由 PresentationSubmissionTool 从 LLM 工具参数构造。
 * <p>
 * 不直接暴露给模型；模型通过扁平的工具参数传递摘要、列表和引用 key。
 * 实际 RenderDocument 由 RenderDocumentAssembler 生成。
 */
@Data
public class PresentationPlan {

    /** 分析结论摘要（纯文本，经 PlainTextPolicy 检查） */
    private String summary;

    /** 要点列表（纯文本，经 PlainTextPolicy 检查） */
    private List<String> bulletItems = new ArrayList<>();

    /** 需要展示的表格 outputKey 列表（引用可信查询结果） */
    private List<String> tableOutputKeys = new ArrayList<>();

    /** 需要生成图表的 outputKey 列表 */
    private List<String> chartOutputKeys = new ArrayList<>();

    /** 区块排列顺序（模型指定，Assembler 验证并可能调整） */
    private List<BlockOrder> blockOrder = new ArrayList<>();

    /**
     * 是否有表格引用。
     */
    public boolean hasTables() {
        return tableOutputKeys != null && !tableOutputKeys.isEmpty();
    }

    /**
     * 是否有图表意图。
     */
    public boolean hasCharts() {
        return chartOutputKeys != null && !chartOutputKeys.isEmpty();
    }

    /**
     * 是否为空计划（无任何有效内容）。
     */
    public boolean isEmpty() {
        return (summary == null || summary.isBlank())
                && (bulletItems == null || bulletItems.isEmpty())
                && !hasTables()
                && !hasCharts();
    }

    /**
     * 区块排列顺序枚举。
     */
    public enum BlockOrder {
        SUMMARY,
        BULLETS,
        TABLE,
        CHART
    }
}
