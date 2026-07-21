package com.AIBI.agent.run;

import com.AIBI.agent.model.PresentationPlan;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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

    /** 本轮前端明确声明的文件范围；不写入工作记忆或 Checkpoint。 */
    @Getter
    private boolean explicitFileScope;

    @Getter
    private Set<Long> fileScopeIds = Set.of();

    /** 显式范围中的文件是否仍全部属于当前对话且处于可用状态。 */
    @Getter
    private boolean fileScopeAvailable = true;

    /** 显式范围在本轮是否已通过 loadData 实时确认。 */
    @Getter
    private boolean fileScopeLoaded = true;

    /** 当前模型调用所属阶段，仅用于调用级成本观测。 */
    @Getter @Setter
    private volatile String modelStage = "unknown";

    // ── 图表暂存（从 AnalysisState 迁移） ──

    /** 本轮 ChartOutputTool 生成的图表 JSON 列表（追加写入，doOnComplete 消费） */
    private final List<String> chartOptions = Collections.synchronizedList(new ArrayList<>());

    /** 通过 validateChart 的图表索引；未校验图表绝不进入最终结果。 */
    private final Set<Integer> validatedChartIndexes = Collections.synchronizedSet(new java.util.LinkedHashSet<>());

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

    /** 展示计划首次就绪信号，用于在 Executor 结束前发送可安全展示的文档快照。 */
    @Getter
    private final CompletableFuture<PresentationPlan> presentationPlanReadyFuture = new CompletableFuture<>();

    /** 前端协商的渲染协议版本（null = 旧协议, "render-document.v1" = 新协议） */
    @Getter @Setter
    private String renderProtocol;

    /** 最后生成的 RenderDocument（由 Assembler 生成，doOnComplete 消费） */
    @Getter @Setter
    private com.AIBI.agent.model.RenderDocument lastRenderDocument;

    /** PresentationSubmissionTool 调用次数（用于限流，超过 MAX_CALLS 降级） */
    private final AtomicInteger presentationCallCount = new AtomicInteger(0);

    /** Executor 与 Synthesizer 的工具调用计数，分别限流以避免一个阶段影响另一个阶段。 */
    private final AtomicInteger executorToolCallCount = new AtomicInteger(0);
    private final AtomicInteger synthesizerToolCallCount = new AtomicInteger(0);

    /** 大结果按索引重算的次数与耗时，仅用于决定是否需要引入缓存。 */
    private final AtomicInteger queryReplayCount = new AtomicInteger(0);
    private final AtomicLong queryReplayElapsedMs = new AtomicLong(0);

    /** 当前运行内展示文档的单调版本，用于 SSE 重放和乱序保护。 */
    private final AtomicInteger documentRevision = new AtomicInteger(0);

    /** 仅用于本轮 SSE 的用户可见活动流；限制回放量，避免慢客户端无界积压。 */
    private final Sinks.Many<RunActivity> activitySink = Sinks.many().replay().limit(64);

    private final AtomicInteger activitySequence = new AtomicInteger(0);

    /** 需求理解活动在意图声明工具返回后立即收口。 */
    private volatile String requirementUnderstandingActivityId;
    private final AtomicBoolean requirementUnderstandingCompleted = new AtomicBoolean(false);

    public RunContext(String runId, Long conversationId) {
        this.runId = runId;
        this.conversationId = conversationId;
    }

    /**
     * 设置本轮文件范围。显式空集合表示本轮不允许访问任何数据文件。
     */
    public void setFileScope(boolean explicit, List<Long> fileIds) {
        this.explicitFileScope = explicit;
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (fileIds != null) {
            fileIds.stream().filter(java.util.Objects::nonNull).forEach(normalized::add);
        }
        this.fileScopeIds = Collections.unmodifiableSet(normalized);
        this.fileScopeAvailable = true;
        this.fileScopeLoaded = !explicit;
    }

    public void markFileScopeUnavailable() {
        if (explicitFileScope) this.fileScopeAvailable = false;
    }

    public void markFileScopeLoaded() {
        if (explicitFileScope) this.fileScopeLoaded = true;
    }

    /** 本轮显式范围内是否允许访问指定文件。 */
    public boolean allowsFile(Long fileId) {
        return !explicitFileScope || (fileScopeAvailable && fileId != null && fileScopeIds.contains(fileId));
    }

    /** 已加载文件必须与本轮显式范围完全一致，避免沿用历史文件。 */
    public boolean matchesFileScope(Collection<String> loadedFileIds) {
        if (!explicitFileScope) return true;
        if (!fileScopeAvailable) return false;
        if (loadedFileIds == null || loadedFileIds.size() != fileScopeIds.size()) return false;
        try {
            return loadedFileIds.stream()
                    .map(Long::valueOf)
                    .allMatch(fileScopeIds::contains);
        } catch (NumberFormatException ignored) {
            return false;
        }
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
        List<String> options = validatedChartOptions();
        if (options.isEmpty()) return null;
        String result = "[" + String.join(",", options) + "]";
        chartOptions.clear();
        validatedChartIndexes.clear();
        return result;
    }

    /**
     * 非消费性查看是否有图表 JSON。
     */
    public String peekChartOptions() {
        List<String> options = validatedChartOptions();
        return options.isEmpty() ? null : "[" + String.join(",", options) + "]";
    }

    /**
     * 是否有图表。
     */
    public boolean hasChartOptions() {
        return !validatedChartIndexes.isEmpty();
    }

    public void markChartValidated(int chartIndex) {
        if (chartIndex >= 1 && chartIndex <= chartOptions.size()) {
            validatedChartIndexes.add(chartIndex);
        }
    }

    private List<String> validatedChartOptions() {
        return validatedChartIndexes.stream()
                .sorted()
                .map(index -> chartOptions.get(index - 1))
                .toList();
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
     * 尝试占用一次工具调用配额。展示计划提交不计入该配额，确保达到上限后仍能正常收口。
     */
    public boolean tryAcquireToolCall(String stage, int maxCalls) {
        AtomicInteger counter = "synthesizer".equals(stage)
                ? synthesizerToolCallCount : executorToolCallCount;
        return counter.incrementAndGet() <= maxCalls;
    }

    public void recordQueryReplay(long elapsedMs) {
        queryReplayCount.incrementAndGet();
        queryReplayElapsedMs.addAndGet(Math.max(0, elapsedMs));
    }

    public QueryReplayMetrics getQueryReplayMetrics() {
        return new QueryReplayMetrics(queryReplayCount.get(), queryReplayElapsedMs.get());
    }

    /** 分配下一次展示文档事件的版本号。 */
    public int nextDocumentRevision() {
        return documentRevision.incrementAndGet();
    }

    public String beginActivity(String stage, String label) {
        return beginActivity(stage, stage, label);
    }

    public String beginRequirementUnderstanding() {
        requirementUnderstandingCompleted.set(false);
        String id = beginActivity("thinking", "requirement-understanding", "正在理解需求");
        requirementUnderstandingActivityId = id;
        return id;
    }

    public void completeRequirementUnderstanding(RunActivity.State state) {
        String id = requirementUnderstandingActivityId;
        if (id == null || !requirementUnderstandingCompleted.compareAndSet(false, true)) return;
        String label = state == RunActivity.State.SUCCEEDED ? "需求理解完成" : "需求理解失败";
        finishActivity(id, "thinking", "requirement-understanding", label, state);
    }

    public String beginActivity(String stage, String toolKey, String label) {
        String id = "activity-" + activitySequence.incrementAndGet();
        emitActivity(new RunActivity(id, stage, toolKey, label, RunActivity.State.RUNNING));
        return id;
    }

    public void finishActivity(String id, String stage, String label, RunActivity.State state) {
        finishActivity(id, stage, stage, label, state);
    }

    public void finishActivity(String id, String stage, String toolKey, String label, RunActivity.State state) {
        emitActivity(new RunActivity(id, stage, toolKey, label, state));
    }

    public Flux<RunActivity> activityEvents() {
        return activitySink.asFlux();
    }

    public void completeActivityEvents() {
        activitySink.tryEmitComplete();
    }

    private synchronized void emitActivity(RunActivity activity) {
        Sinks.EmitResult result = activitySink.tryEmitNext(activity);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED) {
            log.debug("运行活动事件未投递: runId={}, result={}", runId, result);
        }
    }

    /** 保存计划，并发布首次可用快照。 */
    public void publishPresentationPlan(PresentationPlan plan) {
        this.presentationPlan = plan;
        presentationPlanReadyFuture.complete(plan);
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
        completeActivityEvents();
        chartOptions.clear();
        validatedChartIndexes.clear();
        if (!chartReadyFuture.isDone()) {
            chartReadyFuture.complete(null);
        }
        if (!presentationPlanReadyFuture.isDone()) {
            presentationPlanReadyFuture.complete(null);
        }
        clearIntent();
        explicitFileScope = false;
        fileScopeIds = Set.of();
        fileScopeAvailable = true;
        fileScopeLoaded = true;
        log.debug("运行上下文已清理: runId={}", runId);
    }

    public record QueryReplayMetrics(int count, long elapsedMs) {}
}
