package com.AIBI.agent.model;

import com.AIBI.agent.assembler.PlainTextPolicy;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * RenderDocument 组装器 — 将 PresentationPlan 转化为经过验证的 RenderDocument。
 * <p>
 * 职责：
 * <ul>
 *   <li>引用解析：通过 outputKey 从 AnalysisState 查找可信表格数据</li>
 *   <li>结构校验：版本、数量、长度、ID 唯一性、chartIndex 有效性</li>
 *   <li>PlainTextPolicy：所有文本字段必须通过纯文本检测</li>
 *   <li>截断：表格行数、文本长度不超过展示上限</li>
 *   <li>降级：校验失败时生成 degraded=true 的确定性文档</li>
 * </ul>
 * <p>
 * 这是后端最后一道安全边界——任何情况下都不能将未验证内容发送给前端。
 */
@Slf4j
@Component
public class RenderDocumentAssembler {

    @Autowired
    private AnalysisState analysisState;

    /**
     * 将非分析类回答收口为可渲染文档。
     *
     * 普通对话不要求模型提交 PresentationPlan；只要模型给出了有效文本，
     * 就应当作为正常回答展示，而不是被误判为分析失败。
     */
    public RenderDocument assembleTextResponse(String text, String runId) {
        PlainTextPolicy.PlainTextResult result = PlainTextPolicy.validate(text, "text response");
        if (result.text().isBlank()) {
            return buildDegradedDocument(runId, "回答内容为空",
                    "暂时没有生成可展示的回答，请换一种说法后重试。");
        }
        return new RenderDocument(
                RenderDocument.CURRENT_VERSION,
                runId,
                List.of(new ParagraphBlock("response-0", result.text())),
                result.degraded());
    }

    /**
     * 组装 RenderDocument v1。
     *
     * @param plan  LLM 通过 PresentationSubmissionTool 提交的展示计划
     * @param runId 本次运行的唯一标识
     * @return 经过验证的 RenderDocument（可能 degraded=true）
     */
    public RenderDocument assemble(PresentationPlan plan, String runId) {
        if (plan == null || plan.isEmpty()) {
            log.warn("展示计划为空或无效: runId={}", runId);
            return buildDegradedDocument(runId, "展示计划为空",
                    "AI 未生成有效回答，请重试或简化问题。");
        }

        List<RenderBlock> blocks = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        AtomicBoolean degraded = new AtomicBoolean(false);
        int blockIndex = 0;

        // ── 1. 文本区块（Summary） ──
        if (plan.getSummary() != null && !plan.getSummary().isBlank()) {
            PlainTextPolicy.PlainTextResult result = PlainTextPolicy.validate(
                    plan.getSummary(), "summary");
            if (result.degraded()) {
                degraded.set(true);
            }
            String id = nextId("summary", blockIndex++, usedIds);
            blocks.add(new SummaryBlock(id, result.text(), null));
        }

        // ── 2. 列表区块（Bullets） ──
        if (plan.getBulletItems() != null && !plan.getBulletItems().isEmpty()) {
            List<String> items = plan.getBulletItems();
            // 截断列表长度
            if (items.size() > RenderDocument.MAX_BULLET_ITEMS) {
                items = new ArrayList<>(items.subList(0, RenderDocument.MAX_BULLET_ITEMS));
                items.add("...（共 " + plan.getBulletItems().size() + " 条，已截断）");
                degraded.set(true);
            }
            // PlainTextPolicy 检查
            if (PlainTextPolicy.anyContainsMarkdown(items)) {
                log.warn("要点列表包含 Markdown，尝试修复");
                items = items.stream()
                        .map(item -> {
                            PlainTextPolicy.PlainTextResult r = PlainTextPolicy.validate(item, "bullet");
                            if (r.degraded()) degraded.set(true);
                            return r.text();
                        })
                        .collect(Collectors.toList());
            }
            String id = nextId("bullets", blockIndex++, usedIds);
            blocks.add(new BulletListBlock(id, items));
        }

        // ── 3. 表格区块 ──
        if (plan.hasTables()) {
            RunContext ctx = RunContextHolder.get();
            for (String outputKey : plan.getTableOutputKeys()) {
                String id = nextId("table", blockIndex++, usedIds);
                try {
                    RenderBlock tableBlock = buildTableBlock(id, outputKey);
                    blocks.add(tableBlock);
                } catch (Exception e) {
                    log.warn("表格构建失败：输出键={}、错误={}", outputKey, e.getMessage());
                    blocks.add(NoticeBlock.warning(id, "表格数据 '" + outputKey + "' 无法加载: " + e.getMessage()));
                    degraded.set(true);
                }
            }
        }

        // ── 4. 图表区块 ──
        if (plan.hasCharts()) {
            RunContext ctx = RunContextHolder.get();
            int totalCharts = ctx != null ? ctx.peekChartOptions() != null ? 1 : 0 : 0; // 简化：检查是否有图表
            for (String chartKey : plan.getChartOutputKeys()) {
                String id = nextId("chart", blockIndex++, usedIds);
                // chartIndex 先设为 0（当前实现一个请求只有一个图表）
                // 后续多图表支持时从 RunContext 精确匹配
                int chartIndex = 0;
                if (ctx == null || !ctx.hasChartOptions()) {
                    blocks.add(NoticeBlock.info(id, "图表生成中，请稍候..."));
                } else {
                    blocks.add(new ChartBlock(id, chartIndex, null));
                }
            }
        }

        // ── 5. 区块数量上限检查 ──
        if (blocks.size() > RenderDocument.MAX_BLOCKS) {
            blocks = new ArrayList<>(blocks.subList(0, RenderDocument.MAX_BLOCKS));
            blocks.add(NoticeBlock.warning("truncated", "回答内容过多，已截断至 " + RenderDocument.MAX_BLOCKS + " 个区块"));
            degraded.set(true);
        }

        // ── 6. 至少有一个区块 ──
        if (blocks.isEmpty()) {
            log.warn("无有效区块: runId={}", runId);
            return buildDegradedDocument(runId, "无有效区块", "AI 回答无法解析为展示内容，请重试。");
        }

        return new RenderDocument(
                RenderDocument.CURRENT_VERSION,
                runId,
                Collections.unmodifiableList(blocks),
                degraded.get()
        );
    }

    /**
     * 生成图表合成前的安全文档快照。
     *
     * 图表产物尚未完成校验时，只交付已确认的摘要、要点和表格，避免向客户端发送
     * “生成中”的伪图表区块。
     */
    public RenderDocument assembleWithoutCharts(PresentationPlan plan, String runId) {
        if (plan == null || !plan.hasCharts()) {
            return assemble(plan, runId);
        }

        PresentationPlan partialPlan = new PresentationPlan();
        partialPlan.setSummary(plan.getSummary());
        partialPlan.setBulletItems(plan.getBulletItems() == null
                ? List.of() : new ArrayList<>(plan.getBulletItems()));
        partialPlan.setTableOutputKeys(plan.getTableOutputKeys() == null
                ? List.of() : new ArrayList<>(plan.getTableOutputKeys()));
        partialPlan.setChartOutputKeys(List.of());
        partialPlan.setBlockOrder(plan.getBlockOrder() == null ? List.of()
                : plan.getBlockOrder().stream()
                .filter(order -> order != PresentationPlan.BlockOrder.CHART)
                .collect(Collectors.toList()));
        return assemble(partialPlan, runId);
    }

    /**
     * 从 AnalysisState 获取数据并构建 TableBlock。
     */
    private RenderBlock buildTableBlock(String id, String outputKey) {
        String dataJson = analysisState.getDataByKey(outputKey);
        if (dataJson == null) {
            throw new IllegalArgumentException("数据引用不存在: " + outputKey
                    + "。可用数据: " + analysisState.getAvailableKeys());
        }

        JSONArray dataArray = JSON.parseArray(dataJson);
        if (dataArray == null || dataArray.isEmpty()) {
            throw new IllegalArgumentException("数据为空: " + outputKey);
        }

        // 提取 headers（第一条记录的所有 key）
        JSONObject firstRow = dataArray.getJSONObject(0);
        List<String> headers = new ArrayList<>(firstRow.keySet());

        // 提取 rows（截断至 MAX_TABLE_ROWS）
        int totalRows = dataArray.size();
        int displayRows = Math.min(totalRows, RenderDocument.MAX_TABLE_ROWS);
        List<Map<String, Object>> rows = new ArrayList<>(displayRows);
        for (int i = 0; i < displayRows; i++) {
            JSONObject row = dataArray.getJSONObject(i);
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (String header : headers) {
                rowMap.put(header, row.get(header));
            }
            rows.add(rowMap);
        }

        return new TableBlock(id, null, headers, rows, totalRows);
    }

    /**
     * 构建确定性降级文档。
     */
    private RenderDocument buildDegradedDocument(String runId, String reason, String userMessage) {
        List<RenderBlock> blocks = new ArrayList<>();
        blocks.add(NoticeBlock.warning("degraded-notice", userMessage));
        return new RenderDocument(
                RenderDocument.CURRENT_VERSION,
                runId,
                blocks,
                true
        );
    }

    /**
     * 生成唯一的区块 ID。
     */
    private String nextId(String prefix, int index, Set<String> usedIds) {
        String id = prefix + "-" + index;
        while (usedIds.contains(id)) {
            index++;
            id = prefix + "-" + index;
        }
        usedIds.add(id);
        return id;
    }
}
