package com.AIBI.agent.run;

import com.AIBI.agent.model.PresentationPlan;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求级运行上下文 — 替代 AnalysisState 单例可变字段。
 * <p>
 * 每次 Agent 执行（一次 chatStream 调用）创建一个实例，由 {@link RunContextHolder}
 * 通过 ThreadLocal 在当前线程内共享，工具和 Service 层均可访问。
 * <p>
 * 生命周期：AgentServiceImpl.executePipeline 开始时创建 → doFinally 中清除。
 * <p>
 * 线程安全：同一个 runId 不会跨线程并发访问（Agent 单线程执行），
 * chartOptions 按调用顺序追加，chartReadyFuture 是单次 complete → get 的简单信号。
 */
@Slf4j
public class RunContext {

    /** 本次运行的唯一标识（UUID），由 Controller 生成 */
    @Getter
    private final String runId;

    /** 所属对话 ID */
    @Getter
    private final Long conversationId;

    // ── 图表暂存（从 AnalysisState 迁移） ──

    /** 本轮 ChartOutputTool 生成的图表 JSON 列表（追加写入，doOnComplete 消费） */
    private final List<String> chartOptions = Collections.synchronizedList(new ArrayList<>());

    /** 图表就绪信号（ChartOutputTool 完成时触发，用于异步发射 chart SSE 事件） */
    @Getter
    private volatile CompletableFuture<String> chartReadyFuture = new CompletableFuture<>();

    // ── 意图声明（从 AnalysisState 迁移） ──

    /** 意图分类：analysis / chitchat / vague */
    @Getter @Setter
    private String intentCategory;

    /** 分析维度列表（analysis 时填写） */
    @Getter @Setter
    private List<String> intentDimensions;

    /** 分析指标列表（analysis 时填写） */
    @Getter @Setter
    private List<String> intentMetrics;

    /** 清晰度：clear / somewhat / vague */
    @Getter @Setter
    private String intentClarity;

    /** 意图描述 */
    @Getter @Setter
    private String intentSummary;

    /** 输出格式偏好：table / chart / text / 空列表表示未指定 */
    @Getter @Setter
    private List<String> intentOutputFormats;

    // ── 取消状态 ──

    /** 本轮是否已被取消 */
    @Getter @Setter
    private volatile boolean cancelled;

    /** LLM 提交的展示计划（由 PresentationSubmissionTool 写入，Assembler 读取） */
    @Getter @Setter
    private PresentationPlan presentationPlan;

    /** 前端协商的渲染协议版本（null = 旧协议, "render-document.v1" = 新协议） */
    @Getter @Setter
    private String renderProtocol;

    /** 最后生成的 RenderDocument（由 Assembler 生成，doOnComplete 消费） */
    @Getter @Setter
    private com.AIBI.agent.model.RenderDocument lastRenderDocument;

    /** PresentationSubmissionTool 调用次数（用于限流，超过 MAX_CALLS 降级） */
    private final AtomicInteger presentationCallCount = new AtomicInteger(0);

    public RunContext(String runId, Long conversationId) {
        this.runId = runId;
        this.conversationId = conversationId;
    }

    // ── 图表管理（从 AnalysisState 迁移） ──

    /**
     * 追加本轮 ChartOutputTool 生成的图表 JSON。
     * 支持多图表：每次 buildChart 调用追加一条。
     */
    public void addChartOption(String option) {
        chartOptions.add(option);
        chartReadyFuture.complete(option);
    }

    /** 返回最新生成图表的 1-based 引用序号，供工具间传递，不暴露 option JSON。 */
    public int getChartOptionCount() {
        return chartOptions.size();
    }

    /** 按 1-based 引用序号读取图表配置，仅限当前运行期的工具调用。 */
    public String getChartOption(int chartIndex) {
        if (chartIndex < 1 || chartIndex > chartOptions.size()) return null;
        return chartOptions.get(chartIndex - 1);
    }

    /**
     * 消费并清除本轮所有图表 JSON，返回 JSON 数组字符串。
     * 无图表时返回 null。
     */
    public String consumeChartOptions() {
        if (chartOptions.isEmpty()) return null;
        String result = "[" + String.join(",", chartOptions) + "]";
        chartOptions.clear();
        return result;
    }

    /**
     * 非消费性查看是否有图表 JSON。
     */
    public String peekChartOptions() {
        if (chartOptions.isEmpty()) return null;
        return "[" + String.join(",", chartOptions) + "]";
    }

    /**
     * 是否有图表。
     */
    public boolean hasChartOptions() {
        return !chartOptions.isEmpty();
    }

    // ── 意图辅助 ──

    /**
     * 设置意图（兼容原 AnalysisState.setIntent 签名）。
     */
    public void setIntent(String category, List<String> dimensions, List<String> metrics,
                          String clarity, String summary, List<String> outputFormats) {
        this.intentCategory = category;
        this.intentDimensions = dimensions;
        this.intentMetrics = metrics;
        this.intentClarity = clarity;
        this.intentSummary = summary;
        this.intentOutputFormats = outputFormats;
        log.debug("意图已声明：分类={}、清晰度={}", category, clarity);
    }

    /**
     * 是否为分析意图。
     */
    public boolean isAnalysisIntent() {
        return "analysis".equals(intentCategory);
    }

    /**
     * 清除意图（用于守卫拦截后让 Agent 重声明）。
     */
    public void clearIntent() {
        this.intentCategory = null;
        this.intentDimensions = null;
        this.intentMetrics = null;
        this.intentClarity = null;
        this.intentSummary = null;
        this.intentOutputFormats = null;
    }

    /**
     * 获取并递增 PresentationSubmissionTool 调用次数。
     */
    public int getAndIncrementPresentationCallCount() {
        return presentationCallCount.getAndIncrement();
    }

    /**
     * 重置图表就绪信号（每次 Synthesizer 调用前需要新信号）。
     */
    public void resetChartReadyFuture() {
        this.chartReadyFuture = new CompletableFuture<>();
    }

    /**
     * 清除本轮运行状态（doFinally 中调用）。
     * 会触发 chartReadyFuture 完成（如果尚未），防止等待者挂起。
     */
    public void clear() {
        chartOptions.clear();
        if (!chartReadyFuture.isDone()) {
            chartReadyFuture.complete(null);
        }
        clearIntent();
        log.debug("运行上下文已清理: runId={}", runId);
    }
}
