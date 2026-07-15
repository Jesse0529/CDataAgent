package com.AIBI.AgentTool;

import com.AIBI.agent.assembler.PlainTextPolicy;
import com.AIBI.agent.model.PresentationPlan;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.utils.ToolResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 展示计划提交工具 — 替代自由 Markdown 输出的结构化展示协议。
 * <p>
 * Executor Agent 必须在回答结束前调用此工具，提交展示计划。
 * 后端通过 PresentationPlan → RenderDocumentAssembler → RenderDocument 链路
 * 生成经过验证的展示文档。
 * <p>
 * 关键约束（与 Prompt 中的约束同步）：
 * <ul>
 *   <li>summary 和 bulletItems 必须是纯文本，禁止包含 Markdown 格式标记</li>
 *   <li>tableOutputKeys 必须是真实存在的查询输出 key（来自 runDuckdb/runPython 的 outputKey）</li>
 *   <li>chartOutputKeys 是需要生成图表的 outputKey 列表</li>
 *   <li>文本字段由后端统一净化，结构化引用在最终组装时校验</li>
 * </ul>
 */
@Slf4j
@Component
public class PresentationSubmissionTool {

    /** 单次运行中允许的最大调用次数（超限后降级） */
    private static final int MAX_CALLS_PER_RUN = 2;

    /**
     * 提交最终展示计划。
     * <p>
     * 必须在回答结束前调用；不要输出 Markdown 表格或格式标记。
     */
    @Tool(description = """
            提交最终展示计划。必须在回答结束前调用；不要在 summary 和 bulletItems 中使用 ##、**、| 表格、
            ``` 代码块等 Markdown 标记。tableOutputKeys 填写来自 runDuckdb 或 runPython 的真实 outputKey。
            chartOutputKeys 填写需要生成图表的 outputKey 列表。"""
    )
    public String submitPresentation(
            @ToolParam(description = "分析结论摘要（纯文本，禁止 Markdown 标记）") String summary,
            @ToolParam(description = "要点列表（纯文本，禁止 Markdown 标记），无要点时传空数组") List<String> bulletItems,
            @ToolParam(description = "需要展示的表格 outputKey 列表（来自查询工具的输出），无表格时传空数组") List<String> tableOutputKeys,
            @ToolParam(description = "需要生成图表的 outputKey 列表，无图表时传空数组") List<String> chartOutputKeys) {

        RunContext ctx = RunContextHolder.require();
        PresentationPlan existingPlan = ctx.getPresentationPlan();
        int callCount = ctx.getAndIncrementPresentationCallCount();

        // 超限降级
        if (callCount >= MAX_CALLS_PER_RUN) {
            log.warn("展示计划：调用次数超限 ({})", callCount);
            // 仍然接受最后一次提交的内容，但标记降级
            buildAndStorePlan(ctx, summary, bulletItems, tableOutputKeys, chartOutputKeys);
            return "展示计划已提交，但调用次数超限，将使用降级模式。";
        }

        // 空内容检查
        if (isBlank(summary) && isEmpty(bulletItems) && isEmpty(tableOutputKeys) && isEmpty(chartOutputKeys)) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "展示计划不能为空。请至少填写 summary、bulletItems、tableOutputKeys 或 chartOutputKeys 中任意一项。");
        }

        // 文本格式由边界层修复，不把模型的轻微格式偏差升级为一次失败。
        // 结构化引用仍由 Assembler 在最终组装时校验。
        boolean degraded = existingPlan != null; // 第二次调用 → degraded
        buildAndStorePlan(ctx, summary, bulletItems, tableOutputKeys, chartOutputKeys);

        log.debug("展示计划已保存：摘要={}字、要点={}个、表格={}、图表={}",
                summary != null ? summary.length() : 0,
                bulletItems != null ? bulletItems.size() : 0,
                tableOutputKeys != null ? tableOutputKeys.size() : 0,
                chartOutputKeys != null ? chartOutputKeys.size() : 0);

        return "展示计划已提交"
                + (summary != null ? "（摘要 " + summary.length() + " 字）" : "")
                + (bulletItems != null && !bulletItems.isEmpty() ? "（" + bulletItems.size() + " 个要点）" : "")
                + (degraded ? "，使用降级模式" : "");
    }

    private void buildAndStorePlan(RunContext ctx, String summary, List<String> bulletItems,
                                    List<String> tableOutputKeys, List<String> chartOutputKeys) {
        PresentationPlan plan = new PresentationPlan();
        PlainTextPolicy.PlainTextResult summaryResult = PlainTextPolicy.validate(summary, "presentation summary");
        List<String> safeBullets = bulletItems == null ? List.of() : bulletItems.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> PlainTextPolicy.validate(item, "presentation bullet").text())
                .filter(item -> !item.isBlank())
                .toList();

        plan.setSummary(summaryResult.text());
        plan.setBulletItems(safeBullets);
        plan.setTableOutputKeys(tableOutputKeys != null ? tableOutputKeys : List.of());
        plan.setChartOutputKeys(chartOutputKeys != null ? chartOutputKeys : List.of());

        // 按顺序排列
        List<PresentationPlan.BlockOrder> order = new ArrayList<>();
        if (summary != null && !summary.isBlank()) order.add(PresentationPlan.BlockOrder.SUMMARY);
        if (bulletItems != null && !bulletItems.isEmpty()) order.add(PresentationPlan.BlockOrder.BULLETS);
        if (tableOutputKeys != null && !tableOutputKeys.isEmpty()) order.add(PresentationPlan.BlockOrder.TABLE);
        if (chartOutputKeys != null && !chartOutputKeys.isEmpty()) order.add(PresentationPlan.BlockOrder.CHART);
        plan.setBlockOrder(order);

        ctx.setPresentationPlan(plan);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
