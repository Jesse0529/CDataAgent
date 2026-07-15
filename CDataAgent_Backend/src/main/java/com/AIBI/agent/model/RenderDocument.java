package com.AIBI.agent.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;

import java.util.List;
import java.util.Map;

/**
 * RenderDocument v1 — 后端验证后的展示文档，前端渲染的唯一权威来源。
 * <p>
 * 以一次 Agent 执行 (runId) 为边界，在 {@code document} SSE 事件中一次性发送。
 * 持久化为不可变快照，后续 run 不能修改前一消息的文档。
 *
 * @param version  协议版本，固定为 1
 * @param runId    本次运行的唯一标识
 * @param blocks   经过验证的展示区块列表
 * @param degraded 是否触发降级（校验失败后的兜底文档）
 */
public record RenderDocument(
        int version,
        String runId,
        List<RenderBlock> blocks,
        boolean degraded
) {
    /** 当前协议版本 */
    public static final int CURRENT_VERSION = 1;

    /** 单文档最大区块数 */
    public static final int MAX_BLOCKS = 50;

    /** 文本字段最大字符数 */
    public static final int MAX_TEXT_LENGTH = 4000;

    /** 表格最大展示行数（超出截断） */
    public static final int MAX_TABLE_ROWS = 100;

    /** 列表最大条目数 */
    public static final int MAX_BULLET_ITEMS = 30;

    /**
     * 序列化为 JSON 字符串（含 @type 信息，支持 sealed interface 反序列化）。
     * 用于数据库持久化——存储时不丢失具体区块类型。
     */
    public String toJson() {
        return JSON.toJSONString(this, JSONWriter.Feature.WriteClassName);
    }

    /**
     * 序列化为前端传输 JSON（不含 @type 类名，不含 Java 内部类型信息）。
     * 用于 SSE document 事件和历史消息返回。
     */
    public String toTransportJson() {
        return JSON.toJSONString(this);
    }

    /** 将文档拆为可独立追加的传输单元，供流式阶段产物使用。 */
    public List<RenderDocument> splitForStreaming() {
        if (blocks == null || blocks.isEmpty()) return List.of();
        return blocks.stream()
                .map(block -> new RenderDocument(version, runId, List.of(block), degraded))
                .toList();
    }

    /**
     * 从 JSON 反序列化（支持 sealed interface 类型自动解析）。
     */
    public static RenderDocument fromJson(String json) {
        return JSON.parseObject(json, RenderDocument.class, JSONReader.Feature.SupportAutoType);
    }

    /**
     * 从文档中提取结论文本（取第一个 SummaryBlock 的 text）。
     * 用于持久化 conclusion 字段。
     */
    public String extractConclusion() {
        if (blocks == null || blocks.isEmpty()) return null;
        for (RenderBlock block : blocks) {
            if (block instanceof SummaryBlock s) {
                return s.text();
            }
        }
        return null;
    }

    /**
     * 确定性纯文本投影——供旧客户端兼容和搜索索引使用。
     * 这不是 Markdown；新客户端应使用原始 RenderDocument。
     */
    public String toContentProjection() {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (RenderBlock block : blocks) {
            if (block instanceof SummaryBlock s) {
                if (s.text() != null && !s.text().isBlank()) {
                    sb.append(s.text()).append("\n\n");
                }
            } else if (block instanceof ParagraphBlock p) {
                if (p.text() != null && !p.text().isBlank()) {
                    sb.append(p.text()).append("\n\n");
                }
            } else if (block instanceof BulletListBlock b) {
                if (b.items() != null) {
                    for (String item : b.items()) {
                        sb.append("- ").append(item).append("\n");
                    }
                    sb.append("\n");
                }
            } else if (block instanceof TableBlock t) {
                sb.append("[表格: ").append(t.totalRows()).append("行]\n\n");
            } else if (block instanceof ChartBlock c) {
                sb.append("[图表]\n\n");
            } else if (block instanceof NoticeBlock n) {
                if ("warning".equals(n.level())) {
                    sb.append("[").append(n.text()).append("]\n\n");
                }
            }
        }
        return sb.toString().trim();
    }
}

// ─── 区块 sealed interface ─────────────────────────────────

sealed interface RenderBlock
        permits SummaryBlock, ParagraphBlock, BulletListBlock,
                TableBlock, ChartBlock, NoticeBlock {

    /** 区块唯一 ID（单文档内） */
    String id();

    /** 区块类型（对应前端组件选择）。getXxx 命名保障 fastjson2 序列化可见。 */
    String getType();
}

// ─── 6 种区块 record ───────────────────────────────────────

/**
 * 摘要区块 — 模型生成的分析结论纯文本。
 */
record SummaryBlock(String id, String text, String title) implements RenderBlock {
    public String getType() { return "summary"; }
}

/**
 * 段落区块 — 通用纯文本段落。
 */
record ParagraphBlock(String id, String text) implements RenderBlock {
    public String getType() { return "paragraph"; }
}

/**
 * 列表区块 — 要点列表，每条为纯文本。
 */
record BulletListBlock(String id, List<String> items) implements RenderBlock {
    public String getType() { return "bullets"; }
}

/**
 * 表格区块 — 由 Assembler 从可信查询结果生成，不接受模型填写行列。
 */
record TableBlock(String id, String title, List<String> headers,
                  List<Map<String, Object>> rows, int totalRows) implements RenderBlock {
    public String getType() { return "table"; }
}

/**
 * 图表区块 — 引用 ChartOutputTool 已校验的图表配置。
 */
record ChartBlock(String id, int chartIndex, String title) implements RenderBlock {
    public String getType() { return "chart"; }
}

/**
 * 提示区块 — 系统降级或非阻断提醒（info / warning）。
 */
record NoticeBlock(String id, String level, String text) implements RenderBlock {
    public String getType() { return "notice"; }

    /** 创建 info 级别提示 */
    public static NoticeBlock info(String id, String text) {
        return new NoticeBlock(id, "info", text);
    }

    /** 创建 warning 级别提示 */
    public static NoticeBlock warning(String id, String text) {
        return new NoticeBlock(id, "warning", text);
    }
}
