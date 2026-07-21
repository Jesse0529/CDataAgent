package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.config.DuckDbConfig;
import com.AIBI.service.DuckDbQueryService;
import com.AIBI.utils.ToolResultUtils;
import com.AIBI.utils.OutputKeyPolicy;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DuckDB 查询工具：供 ExecutorAgent 使用。
 * <p>
 * 执行 SQL 查询（聚合、筛选、统计），自动注册当前对话的所有 Parquet 文件为视图。
 * 支持多文件 JOIN：SQL 中通过 viewName 引用具体文件。
 */
@Slf4j
@Component
public class DuckDbQueryTool {

    private static final int MAX_INLINE_RESULT_ROWS = 200;
    private static final int MAX_INLINE_RESULT_BYTES = 32 * 1024;

    /** Agent 结果会进入 AnalysisState 并参与后续图表，必须远小于通用查询上限。 */
    @Value("${agent.query.max-result-rows:1000}")
    private int agentMaxResultRows;

    @Autowired
    private DuckDbQueryService duckDbQueryService;

    @Autowired
    private AnalysisState analysisState;

    /**
     * 跨轮次 SQL 查询结果缓存（Caffeine 本地缓存）。
     * <p>
     * TTL 60 秒，最多 100 条。按 SQL + viewNames 的 SHA256 hash 索引。
     * 文件删除时通过 TTL 自然过期。
     */
    private static final Cache<String, CachedQueryResult> sqlCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();

    // ─── 意图守卫 ─────────────────────────────────────────

    private String checkIntentGuard() {
        RunContext ctx = RunContextHolder.require();
        String category = ctx.getIntentCategory();
        if (category == null) {
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                    "请先调用 declareIntent 声明意图后再查询数据。");
        }
        if (!"analysis".equals(category)) {
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                    "当前意图分类为 " + category + "，无需查询数据。请直接回复用户。");
        }
        return null; // 放行
    }

    /** 当前范围已显式声明时，查询只能使用该范围完整加载出的文件。 */
    private String checkFileScope(List<AnalysisState.LoadedFileRecord> files) {
        RunContext context = RunContextHolder.require();
        if (context.isExplicitFileScope() && !context.isFileScopeLoaded()) {
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                    "本轮文件范围尚未确认，请先调用 loadData 获取当前可用文件。");
        }
        if (!context.matchesFileScope(files.stream().map(file -> file.fileId).toList())) {
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                    "当前文件范围尚未完整加载，请先调用 loadData。");
        }
        return null;
    }

    /**
     * 执行 DuckDB SQL 查询。
     * <p>
     * 自动注册当前对话的所有 Parquet 文件为视图，SQL 中通过 viewName 引用。
     * 单文件示例：SELECT region, SUM(sales) FROM data_1001_abc123 GROUP BY region
     * 多文件 JOIN 示例：SELECT a.region, a.sales, b.target FROM data_1001_abc123 a JOIN data_1001_def456 b ON a.region=b.region
     */
    @Tool(description = "对已加载视图执行只读 SQL：聚合、筛选、排序、统计或 JOIN。结果保存为 outputKey；截断结果需重查。")
    public String runDuckdb(
            @ToolParam(description = "SELECT SQL；表名使用 viewName") String sql,
            @ToolParam(description = "结果引用，如 monthly_sales") String outputKey) {
        // 意图守卫
        String guardResult = checkIntentGuard();
        if (guardResult != null) return guardResult;
        if (!OutputKeyPolicy.isValid(outputKey)) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "outputKey 仅支持字母开头的字母、数字和下划线，长度不超过64");
        }

        try {
            List<AnalysisState.LoadedFileRecord> files = analysisState.getLoadedFiles();
            if (files == null || files.isEmpty()) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "没有已加载的数据文件，请先调用 loadData");
            }
            String scopeError = checkFileScope(files);
            if (scopeError != null) return scopeError;

            // 回合内去重：同一 outputKey 已有结果时直接返回
            AnalysisState.QueryOutputRecord existing = analysisState.getQueryOutput(outputKey);
            if (existing != null) {
                return buildSummary(outputKey, existing.rowCount, existing.rowLimit, existing.truncated,
                        existing.sampleJson, true).toJSONString();
            }

            // 构建 FileRef 列表
            List<DuckDbConfig.FileRef> refs = files.stream()
                    .map(f -> new DuckDbConfig.FileRef(f.parquetPath, f.viewName))
                    .collect(Collectors.toList());

            // 跨轮次 SQL 缓存（输出键不同但 SQL 可能相同）
            String cacheKey = buildCacheKey(refs, sql);
            CachedQueryResult cachedResult = sqlCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                JSONArray cachedData = JSON.parseArray(cachedResult.dataJson());
                saveQueryOutput(outputKey, sql, refs, cachedData, cachedResult.rowCount(),
                        cachedResult.rowLimit(), cachedResult.truncated(), "runDuckdb");
                log.info("DuckDB查询：命中本地缓存 输出键={} 行数={} 截断={}",
                        outputKey, cachedResult.rowCount(), cachedResult.truncated());
                return buildSummary(outputKey, cachedResult.rowCount(), cachedResult.rowLimit(),
                        cachedResult.truncated(), sampleJson(cachedData), true).toJSONString();
            }

            String conversationId = RunContextHolder.require().getConversationId().toString();
            DuckDbQueryService.AgentQueryResult result = duckDbQueryService
                    .executeAgentQuery(conversationId, refs, sql, agentMaxResultRows);

            // 瞬态错误自动重试一次（system/timeout）
            if (result != null && ToolResultUtils.isTransientError(result.dataJson())) {
                log.warn("DuckDB查询：瞬态错误，重试一次");
                result = duckDbQueryService.executeAgentQuery(conversationId, refs, sql, agentMaxResultRows);
            }

            if (result == null || result.hasError()) {
                return result == null ? ToolResultUtils.jsonTypedError("system", "查询未返回结果") : result.dataJson();
            }

            // 存入 Caffeine 缓存（跨轮次复用）
            sqlCache.put(cacheKey, new CachedQueryResult(result.dataJson(), result.rowCount(),
                    result.rowLimit(), result.truncated()));

            // 存入分析状态
            JSONArray data = JSON.parseArray(result.dataJson());
            saveQueryOutput(outputKey, sql, refs, data, result.rowCount(), result.rowLimit(),
                    result.truncated(), "runDuckdb");

            log.info("DuckDB查询：输出键={}、行数={}、截断={}、视图数={}",
                    outputKey, result.rowCount(), result.truncated(), files.size());
            return buildSummary(outputKey, result.rowCount(), result.rowLimit(), result.truncated(),
                    sampleJson(data), false).toJSONString();
        } catch (Exception e) {
            log.error("DuckDB查询失败", e);
            analysisState.addStepResultFailed(outputKey, "runDuckdb", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "查询异常: " + e.getMessage());
        }
    }

    /**
     * 批量数值统计（快速获取多列的 COUNT/AVG/MIN/MAX/STDDEV/PERCENTILE）。
     * <p>
     * 在所有已加载文件的第一个文件上执行（如需指定文件，请用 runDuckdb 手写 SQL）。
     */
    @Tool(description = "计算数值列的 count、均值、极值、标准差和四分位数；仅用于分布概览。")
    public String queryStatistics(
            @ToolParam(description = "数值列名，逗号分隔") String columns) {
        // 意图守卫
        String guardResult = checkIntentGuard();
        if (guardResult != null) return guardResult;

        String statsKey = columns + "_stats";
        try {
            List<AnalysisState.LoadedFileRecord> files = analysisState.getLoadedFiles();
            if (files == null || files.isEmpty()) return ToolResultUtils.jsonTypedError(
                    ToolResultUtils.ERROR_PRECONDITION, "没有已加载的数据文件");
            String scopeError = checkFileScope(files);
            if (scopeError != null) return scopeError;

            // 回合内去重
            String existing = analysisState.getDataByKey(statsKey);
            if (existing != null) {
                log.info("统计查询：命中缓存 列={}", columns);
                return existing;
            }

            // 在当前架构下，统计默认针对第一个文件。如需跨文件统计，Agent 应使用 runDuckdb。
            AnalysisState.LoadedFileRecord activeFile = files.get(files.size() - 1);

            List<String> cols = parseStatisticsColumns(columns, activeFile);
            if (cols.isEmpty()) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "统计字段为空或不属于当前文件；可用字段：" + availableColumns(activeFile));
            }
            StringBuilder sqlBuilder = new StringBuilder("SELECT");
            for (int i = 0; i < cols.size(); i++) {
                String col = cols.get(i);
                if (i > 0) sqlBuilder.append(",");
                sqlBuilder.append(" COUNT(\"").append(col).append("\") AS ").append(col).append("_count");
                sqlBuilder.append(", AVG(\"").append(col).append("\") AS ").append(col).append("_avg");
                sqlBuilder.append(", MIN(\"").append(col).append("\") AS ").append(col).append("_min");
                sqlBuilder.append(", MAX(\"").append(col).append("\") AS ").append(col).append("_max");
                sqlBuilder.append(", STDDEV(\"").append(col).append("\") AS ").append(col).append("_stddev");
                sqlBuilder.append(", PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY \"").append(col)
                        .append("\") AS ").append(col).append("_q25");
                sqlBuilder.append(", PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY \"").append(col)
                        .append("\") AS ").append(col).append("_median");
                sqlBuilder.append(", PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY \"").append(col)
                        .append("\") AS ").append(col).append("_q75");
            }
            sqlBuilder.append(" FROM ").append(activeFile.viewName);

            List<DuckDbConfig.FileRef> refs = List.of(
                    new DuckDbConfig.FileRef(activeFile.parquetPath, activeFile.viewName));
            String querySql = sqlBuilder.toString();
            String cacheKey = buildCacheKey(refs, querySql);
            CachedQueryResult cachedResult = sqlCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                log.info("统计查询：Caffeine缓存命中 列={}", columns);
                JSONArray data = JSON.parseArray(cachedResult.dataJson());
                saveQueryOutput(statsKey, querySql, refs, data, cachedResult.rowCount(),
                        cachedResult.rowLimit(), cachedResult.truncated(), "queryStatistics");
                return cachedResult.dataJson();
            }
            String conversationId = RunContextHolder.require().getConversationId().toString();
            DuckDbQueryService.AgentQueryResult result = duckDbQueryService
                    .executeAgentQuery(conversationId, refs, querySql, agentMaxResultRows);

            // 瞬态错误自动重试一次
            if (result != null && ToolResultUtils.isTransientError(result.dataJson())) {
                log.warn("统计查询：瞬态错误，重试一次 列={}", columns);
                result = duckDbQueryService.executeAgentQuery(conversationId, refs, sqlBuilder.toString(), agentMaxResultRows);
            }

            if (result == null || result.hasError()) {
                return result == null ? ToolResultUtils.jsonTypedError("system", "统计查询未返回结果") : result.dataJson();
            }

            sqlCache.put(cacheKey, new CachedQueryResult(result.dataJson(), result.rowCount(),
                    result.rowLimit(), result.truncated()));
            saveQueryOutput(statsKey, querySql, refs, JSON.parseArray(result.dataJson()), result.rowCount(),
                    result.rowLimit(), result.truncated(), "queryStatistics");

            return result.dataJson();
        } catch (Exception e) {
            log.error("统计查询失败", e);
            analysisState.addStepResultFailed(statsKey, "queryStatistics", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "统计查询异常: " + e.getMessage());
        }
    }

    /**
     * 构建 Caffeine 缓存 key：viewNames 排序拼接 + SQL → SHA256。
     */
    private static String buildCacheKey(List<DuckDbConfig.FileRef> refs, String sql) {
        String viewPart = refs.stream()
                .map(r -> r.viewName())
                .sorted()
                .collect(Collectors.joining(","));
        String rawKey = viewPart + "|" + sql;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            // 降级：用 hashCode 避免阻断查询
            return "fallback_" + rawKey.hashCode();
        }
    }

    private void saveQueryOutput(String outputKey, String sql, List<DuckDbConfig.FileRef> refs,
                                 JSONArray data, int rowCount, int rowLimit, boolean truncated, String toolName) {
        String dataJson = data == null ? "[]" : data.toJSONString();
        boolean inline = rowCount <= MAX_INLINE_RESULT_ROWS
                && dataJson.getBytes(StandardCharsets.UTF_8).length <= MAX_INLINE_RESULT_BYTES;

        AnalysisState.QueryOutputRecord output = new AnalysisState.QueryOutputRecord();
        output.outputKey = outputKey;
        output.sql = sql;
        output.sources = refs.stream()
                .map(ref -> new AnalysisState.QuerySourceRecord(ref.parquetPath(), ref.viewName()))
                .collect(Collectors.toList());
        output.fields = data == null || data.isEmpty() || data.getJSONObject(0) == null
                ? List.of() : List.copyOf(data.getJSONObject(0).keySet());
        output.sampleJson = sampleJson(data);
        output.rowCount = rowCount;
        output.rowLimit = rowLimit;
        output.truncated = truncated;
        output.storageMode = inline ? "inline" : "requery";

        if (inline) {
            analysisState.addData(outputKey, dataJson);
        }
        analysisState.addQueryOutput(output);
        analysisState.addStepResult(outputKey, toolName, rowCount, sql);
    }

    private static JSONObject buildSummary(String outputKey, int rowCount, int rowLimit, boolean truncated,
                                           String sampleJson, boolean cached) {
        JSONObject summary = new JSONObject();
        summary.put("outputKey", outputKey);
        summary.put("rows", rowCount);
        summary.put("rowLimit", rowLimit);
        summary.put("truncated", truncated);
        summary.put("cached", cached);
        summary.put("sample", sampleJson == null ? JSONArray.of() : JSON.parseArray(sampleJson));
        if (truncated) {
            summary.put("notice", "结果仅返回前" + rowLimit + "行，不能据此得出完整结论或直接制图；请先聚合、筛选或使用 Top N 查询。");
        }
        return summary;
    }

    private static String sampleJson(JSONArray data) {
        if (data == null || data.isEmpty()) return "[]";
        return JSON.toJSONString(data.subList(0, Math.min(3, data.size())));
    }

    private record CachedQueryResult(String dataJson, int rowCount, int rowLimit, boolean truncated) {}

    /** 统计工具只接受 loadData 已暴露的真实字段，避免猜测列名进入 DuckDB。 */
    private static List<String> parseStatisticsColumns(String columns,
                                                       AnalysisState.LoadedFileRecord activeFile) {
        if (columns == null || columns.isBlank() || activeFile.columns == null || activeFile.columns.isEmpty()) {
            return List.of();
        }
        Set<String> available = activeFile.columns.stream()
                .map(column -> column.name)
                .collect(Collectors.toSet());
        Set<String> requested = new LinkedHashSet<>();
        for (String raw : columns.split(",", -1)) {
            String column = raw.trim();
            if (column.isEmpty() || !available.contains(column)) {
                return List.of();
            }
            requested.add(column);
        }
        return new ArrayList<>(requested);
    }

    private static String availableColumns(AnalysisState.LoadedFileRecord activeFile) {
        if (activeFile.columns == null) return "无";
        return activeFile.columns.stream()
                .map(column -> column.name)
                .limit(30)
                .collect(Collectors.joining(", "));
    }

    /**
     * 截断 SQL 日志输出（过长时保留前 200 字符）。
     */
    private static String truncateSql(String sql) {
        return sql != null && sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }

}
