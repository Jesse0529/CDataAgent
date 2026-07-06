package com.AIBI.agent.model;

import com.AIBI.config.AnalysisStateStore;
import com.AIBI.model.entity.DataFile;
import com.AIBI.service.FileConversionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 分析状态管理器 — Agent 工作记忆的核心实现。
 * <p>
 * 在 LLM 上下文之外维护结构化的分析进度，每轮对话开始时只注入 ~200 token 摘要。
 * 数据存储：内存（ConcurrentHashMap），请求间通过 Redis 持久化（后续迭代）。
 * <p>
 * 多文件支持：一个对话可以绑定多个数据文件，查询时自动注册全部视图。
 */
@Slf4j
@Component
public class AnalysisState {

    /** 内存缓存：threadId → 已加载文件列表 */
    private final ConcurrentHashMap<String, List<LoadedFileRecord>> loadedFiles = new ConcurrentHashMap<>();

    /** 内存缓存：threadId → 步骤结果列表 */
    private final ConcurrentHashMap<String, List<StepRecord>> stepResults = new ConcurrentHashMap<>();

    /** 内存缓存：threadId → outputKey → dataJson */
    private final ConcurrentHashMap<String, Map<String, String>> dataIndex = new ConcurrentHashMap<>();

    /** 内存缓存：threadId → 本轮消息指定的 active 文件 ID 列表（前端传参决定） */
    private final ConcurrentHashMap<String, List<Long>> activeFileIds = new ConcurrentHashMap<>();

    private static final int MAX_RECENT_STEPS = 20;
    private static final int MAX_LOADED_FILES = 10;

    private String currentThreadId;

    /** 暂存本轮生成的图表 JSON 列表（由 ChartOutputTool 追加，doOnComplete 消费） */
    private final List<String> chartOptions = new ArrayList<>();

    /** 图表就绪信号（ChartOutputTool 完成时触发，Synthesizer 流中异步发射 chart 事件） */
    private CompletableFuture<String> chartReadyFuture = new CompletableFuture<>();

    /** Redis 持久化存储（可选，不可用时静默降级为纯内存模式） */
    @Autowired(required = false)
    private AnalysisStateStore analysisStateStore;

    // ─── 意图声明（三层架构 Layer 2 结构化输出） ──────────

    /** 意图分类：analysis / chitchat / vague */
    private String intentCategory;

    /** 分析维度列表（analysis 时填写） */
    private List<String> intentDimensions;

    /** 分析指标列表（analysis 时填写） */
    private List<String> intentMetrics;

    /** 清晰度：clear / somewhat / vague */
    private String intentClarity;

    /** 意图描述 */
    private String intentSummary;

    /** 输出格式偏好：table / chart / text / 空列表表示未指定 */
    private List<String> intentOutputFormats;

    public void setCurrentThreadId(String threadId) {
        this.currentThreadId = threadId;
    }

    public String getCurrentThreadId() {
        return currentThreadId;
    }

    // ─── 本轮 active 文件管理 ────────────────────

    /**
     * 设置本轮消息指定的 active 文件 ID 列表。
     * 前端每轮发送消息时传入 fileIds，后端据此确定 agent 可操作的范围。
     */
    public void setActiveFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            activeFileIds.remove(currentThreadId);
        } else {
            activeFileIds.put(currentThreadId, new ArrayList<>(fileIds));
        }
    }

    /**
     * 获取本轮消息指定的 active 文件 ID 列表。
     */
    public List<Long> getActiveFileIds() {
        return activeFileIds.getOrDefault(currentThreadId, Collections.emptyList());
    }

    /**
     * 清除本轮 active 文件 ID（文件切换时触发）。
     */
    public void clearActiveFileIds() {
        activeFileIds.remove(currentThreadId);
    }

    /**
     * 是否有本轮消息指定的 active 文件。
     */
    public boolean hasActiveFileIds() {
        List<Long> ids = activeFileIds.get(currentThreadId);
        return ids != null && !ids.isEmpty();
    }

    // ─── 文件管理（多文件） ──────────────────────

    /**
     * 添加已加载的文件到分析状态。
     */
    public void addLoadedFile(DataFile dataFile, FileConversionService.SchemaInfo schema) {
        LoadedFileRecord record = new LoadedFileRecord();
        record.fileId = dataFile.getId().toString();
        record.originalFilename = dataFile.getOriginalFilename();
        record.viewName = dataFile.getViewName();
        record.parquetPath = dataFile.getStoragePath();
        record.rowCount = schema.rowCount;
        record.columns = schema.columns.stream()
                .map(c -> new ColumnRecord(c.name, c.type, c.samples))
                .collect(Collectors.toList());

        List<LoadedFileRecord> files = loadedFiles.computeIfAbsent(currentThreadId, k -> new ArrayList<>());

        // 去重：同 viewName 覆盖
        files.removeIf(f -> f.viewName.equals(record.viewName));

        if (files.size() >= MAX_LOADED_FILES) {
            files.remove(0); // FIFO 淘汰最早的
        }
        files.add(record);

        log.info("AnalysisState: 文件已加载: {} ({}行, {}列), 当前共 {} 个文件",
                record.viewName, record.rowCount, record.columns.size(), files.size());
    }

    /**
     * 获取当前对话所有已加载文件。
     */
    public List<LoadedFileRecord> getLoadedFiles() {
        return loadedFiles.getOrDefault(currentThreadId, Collections.emptyList());
    }

    /**
     * @deprecated 使用 {@link #getLoadedFiles()} 替代。
     */
    @Deprecated
    public LoadedFileRecord getActiveFile() {
        List<LoadedFileRecord> files = getLoadedFiles();
        return files.isEmpty() ? null : files.get(files.size() - 1); // 返回最新加载的
    }

    /**
     * 批量加载文件（用于首次加载或 full reload）。
     */
    public void setLoadedFiles(List<LoadedFileRecord> files) {
        loadedFiles.put(currentThreadId, new ArrayList<>(files));
    }

    // ─── 步骤管理 ────────────────────────────────

    /**
     * 添加步骤记录（兼容旧调用方，默认 SUCCESS）。
     */
    public void addStepResult(String outputKey, String toolName, int rowCount, String detail) {
        addStepResult(outputKey, toolName, rowCount, detail, "SUCCESS", null);
    }

    /**
     * 添加步骤记录（完整参数）。
     *
     * @param status       "SUCCESS" 或 "FAILED"
     * @param errorMessage 失败时的详细错误信息（成功时传 null）
     */
    public void addStepResult(String outputKey, String toolName, int rowCount, String detail,
                               String status, String errorMessage) {
        StepRecord step = new StepRecord();
        step.stepId = "step_" + System.currentTimeMillis();
        step.toolName = toolName;
        step.outputKey = outputKey;
        step.rowCount = rowCount;
        step.status = status != null ? status : "SUCCESS";
        step.errorMessage = errorMessage;

        List<StepRecord> steps = stepResults.computeIfAbsent(currentThreadId, k -> new ArrayList<>());
        steps.add(step);
        if (steps.size() > MAX_RECENT_STEPS) steps.remove(0);

        if ("FAILED".equals(status)) {
            log.warn("AnalysisState: 步骤失败: {} → {} (error={})", toolName, outputKey, errorMessage);
        } else {
            log.info("AnalysisState: 步骤完成: {} → {} ({}行)", toolName, outputKey, rowCount);
        }
    }

    /**
     * 便捷方法：记录失败步骤。
     */
    public void addStepResultFailed(String outputKey, String toolName, String errorMessage) {
        addStepResult(outputKey, toolName, 0, null, "FAILED", errorMessage);
    }

    public void addData(String outputKey, String dataJson) {
        dataIndex.computeIfAbsent(currentThreadId, k -> new LinkedHashMap<>())
                .put(outputKey, dataJson);
    }

    public String getDataByKey(String outputKey) {
        Map<String, String> map = dataIndex.get(currentThreadId);
        return map != null ? map.get(outputKey) : null;
    }

    public Set<String> getAvailableKeys() {
        Map<String, String> map = dataIndex.get(currentThreadId);
        return map != null ? map.keySet() : Collections.emptySet();
    }

    public List<StepRecord> getSteps() {
        return stepResults.getOrDefault(currentThreadId, Collections.emptyList());
    }

    // ─── 清理与重置 ──────────────────────────────

    /**
     * 清除当前对话的分析状态（单轮结束后）。
     * <p>
     * 清除前先将关键数据持久化到 Redis（如果 Store 可用），
     * 使下一轮对话能从 Redis 恢复已加载文件、步骤和 SQL 结果。
     */
    public void clear() {
        // 持久化到 Redis（在清空之前）
        if (analysisStateStore != null && currentThreadId != null) {
            analysisStateStore.save(currentThreadId, this);
        }
        loadedFiles.remove(currentThreadId);
        stepResults.remove(currentThreadId);
        dataIndex.remove(currentThreadId);
        activeFileIds.remove(currentThreadId);
        chartOptions.clear();
        chartReadyFuture = new CompletableFuture<>();
        clearIntent();
        log.debug("AnalysisState: 已清理 threadId={}", currentThreadId);
    }

    /**
     * 从 Redis 恢复上一轮持久化的分析状态。
     * <p>
     * 每轮对话开始前调用（在 {@code injectContext} 中），
     * 使 Agent 能跨轮复用已加载文件、执行步骤和 SQL 结果。
     * <p>
     * 仅恢复结构化数据（文件、步骤、dataIndex），不恢复 intent（每轮重新声明）。
     *
     * @return true 表示成功从 Redis 恢复了数据，false 表示无状态可恢复或恢复失败
     */
    public boolean restoreFromRedis(String conversationId) {
        if (analysisStateStore == null || conversationId == null) {
            return false;
        }
        // 使用 restore() 的超集调用
        AnalysisStateStore.StateSnapshot snapshot = analysisStateStore.load(conversationId);
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        this.currentThreadId = conversationId;
        // 恢复文件、步骤和 SQL 结果，但不恢复 activeFileIds
        // activeFileIds 由前端每轮传入，始终以当前请求为准
        restore(conversationId, snapshot.files(), snapshot.steps(),
                snapshot.dataIndex(), null);
        log.info("AnalysisState: 已从 Redis 恢复 conversationId={}, files={}, steps={}, dataEntries={}",
                conversationId, snapshot.files().size(), snapshot.steps().size(), snapshot.dataIndex().size());
        return true;
    }

    /**
     * 重置指定对话的分析状态（文件替换 / 文件删除 / 对话重置时调用）。
     * <p>
     * 同时会删除 Redis 中的持久化数据，确保重置后状态完全清除。
     */
    public void resetByConversation(String conversationId) {
        loadedFiles.remove(conversationId);
        stepResults.remove(conversationId);
        dataIndex.remove(conversationId);
        activeFileIds.remove(conversationId);
        chartOptions.clear();
        chartReadyFuture = new CompletableFuture<>();
        if (analysisStateStore != null) {
            analysisStateStore.delete(conversationId);
        }
        log.info("AnalysisState: 已重置 conversationId={}", conversationId);
    }

    /**
     * 设置状态上下文（续接已有对话时）。
     */
    public void restore(String conversationId, List<LoadedFileRecord> files,
                         List<StepRecord> steps, Map<String, String> data,
                         List<Long> fileIds) {
        this.currentThreadId = conversationId;
        if (files != null) loadedFiles.put(conversationId, new ArrayList<>(files));
        if (steps != null) stepResults.put(conversationId, new ArrayList<>(steps));
        if (data != null) dataIndex.put(conversationId, new LinkedHashMap<>(data));
        if (fileIds != null && !fileIds.isEmpty()) activeFileIds.put(conversationId, new ArrayList<>(fileIds));
    }

    // ─── 图表 JSON 暂存（tool result → state → 持久化） ─────

    /**
     * 追加本轮 ChartOutputTool 生成的图表 JSON。
     * 支持多图表：每次 buildChart 调用追加一条。
     */
    public void addChartOption(String option) {
        chartOptions.add(option);
        // 信号通知：图表已就绪，Streaming 管道可以发射 chart 事件
        chartReadyFuture.complete(option);
    }

    /**
     * 获取图表就绪信号（用于 Synthesizer 流中异步发射 chart 事件）。
     * 每次调用返回新实例（一次性的）。
     */
    public CompletableFuture<String> getChartReadyFuture() {
        return chartReadyFuture;
    }

    /**
     * 消费并清除本轮所有图表 JSON，返回 JSON 数组字符串（如 [{...}, {...}]）。
     * 无图表时返回 null。
     */
    public String consumeChartOptions() {
        if (chartOptions.isEmpty()) return null;
        String result = "[" + String.join(",", chartOptions) + "]";
        chartOptions.clear();
        return result;
    }

    /**
     * 非消费性查看是否有图表 JSON（用于在 Synthesizer 文本流中先发 chart 事件）。
     */
    public String peekChartOptions() {
        if (chartOptions.isEmpty()) return null;
        return "[" + String.join(",", chartOptions) + "]";
    }

    // ─── 意图声明 ─────────────────────────────────────────

    public void setIntent(String category, List<String> dimensions, List<String> metrics,
                           String clarity, String summary, List<String> outputFormats) {
        this.intentCategory = category;
        this.intentDimensions = dimensions;
        this.intentMetrics = metrics;
        this.intentClarity = clarity;
        this.intentSummary = summary;
        this.intentOutputFormats = outputFormats;
        log.debug("意图已声明: category={}, clarity={}, dimensions={}, metrics={}, outputFormats={}",
                category, clarity, dimensions, metrics, outputFormats);
    }

    public String getIntentCategory() { return intentCategory; }
    public List<String> getIntentDimensions() { return intentDimensions; }
    public List<String> getIntentMetrics() { return intentMetrics; }
    public String getIntentClarity() { return intentClarity; }
    public String getIntentSummary() { return intentSummary; }
    public List<String> getIntentOutputFormats() { return intentOutputFormats; }

    public boolean isAnalysisIntent() {
        return "analysis".equals(intentCategory);
    }

    /**
     * 清除当前 intent（用于守卫拦截后让 Agent 重声明）。
     */
    public void clearIntent() {
        this.intentCategory = null;
        this.intentDimensions = null;
        this.intentMetrics = null;
        this.intentClarity = null;
        this.intentSummary = null;
        this.intentOutputFormats = null;
    }

    // ─── 生成上下文摘要（注入到 LLM context） ─────

    public String toContextString() {
        StringBuilder sb = new StringBuilder();
        List<LoadedFileRecord> files = getLoadedFiles();

        if (files.isEmpty()) {
            sb.append("[分析状态] 尚未加载数据文件。用户已上传文件？请调用 loadData。");
            return sb.toString();
        }

        sb.append("[分析状态]\n");

        if (files.size() == 1) {
            LoadedFileRecord f = files.get(0);
            sb.append("已加载文件: ").append(f.originalFilename)
                    .append(" (").append(f.rowCount).append("行, ")
                    .append(f.columns.size()).append("列)\n");
            sb.append("  viewName: ").append(f.viewName).append("\n");
        } else {
            sb.append("已加载 ").append(files.size()).append(" 个文件:\n");
            for (LoadedFileRecord f : files) {
                sb.append("  ").append(f.viewName).append(" ← ")
                        .append(f.originalFilename)
                        .append(" (").append(f.rowCount).append("行, ")
                        .append(f.columns.size()).append("列)\n");
            }
            // 列出所有文件的列名汇总，帮 Agent 判断 JOIN 可行性
            sb.append("列名汇总: ");
            Map<String, String> colToFile = new LinkedHashMap<>();
            for (LoadedFileRecord f : files) {
                for (ColumnRecord c : f.columns) {
                    colToFile.putIfAbsent(c.name, f.viewName);
                }
            }
            sb.append(colToFile.entrySet().stream()
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        List<StepRecord> steps = getSteps();
        if (!steps.isEmpty()) {
            sb.append("执行步骤:\n");
            for (StepRecord s : steps) {
                if ("FAILED".equals(s.status)) {
                    sb.append("  ❌ ").append(s.toolName).append(" → ").append(s.outputKey);
                    if (s.errorMessage != null) sb.append(" [").append(s.errorMessage).append("]");
                } else {
                    sb.append("  ").append(s.toolName).append(" → ").append(s.outputKey);
                    if (s.rowCount > 0) sb.append(" (").append(s.rowCount).append("行)");
                }
                sb.append("\n");
            }
        }

        Set<String> keys = getAvailableKeys();
        if (!keys.isEmpty()) {
            sb.append("可用数据: ").append(String.join(", ", keys));
        }

        return sb.toString();
    }

    // ─── 内部类 ──────────────────────────────────

    @Data
    public static class LoadedFileRecord {
        public String fileId;
        public String originalFilename;
        public String viewName;
        public String parquetPath;
        public int rowCount;
        public List<ColumnRecord> columns;
    }

    @Data
    public static class ColumnRecord {
        public String name;
        public String type;
        public List<String> samples;
        public ColumnRecord() {}
        public ColumnRecord(String name, String type, List<String> samples) {
            this.name = name; this.type = type; this.samples = samples;
        }
    }

    @Data
    public static class StepRecord {
        public String stepId;
        public String toolName;
        public String outputKey;
        public int rowCount;
        public String status;
        /** 失败时的详细错误信息（成功时为 null） */
        public String errorMessage;
    }
}
