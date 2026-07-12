package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.config.DuckDbConfig;
import com.AIBI.service.DuckDbQueryService;
import com.AIBI.utils.ToolResultUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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
    private static final Cache<String, String> sqlCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();

    // ─── 意图守卫 ─────────────────────────────────────────

    private String checkIntentGuard() {
        String category = analysisState.getIntentCategory();
        if (category == null) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "请先调用 declareIntent 声明意图后再查询数据。");
        }
        if (!"analysis".equals(category)) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "当前意图分类为 " + category + "，无需查询数据。请直接回复用户。");
        }
        return null; // 放行
    }

    /**
     * 执行 DuckDB SQL 查询。
     * <p>
     * 自动注册当前对话的所有 Parquet 文件为视图，SQL 中通过 viewName 引用。
     * 单文件示例：SELECT region, SUM(sales) FROM data_1001_abc123 GROUP BY region
     * 多文件 JOIN 示例：SELECT a.region, a.sales, b.target FROM data_1001_abc123 a JOIN data_1001_def456 b ON a.region=b.region
     */
    @Tool(description = "对已加载的数据执行 SQL 查询（仅 SELECT）。" +
            "✅ 聚合/筛选/排序/统计/JOIN ❌ 不用于建表/删表。" +
            "多文件时用 viewName 引用具体文件，支持跨文件 JOIN。" +
            "loadData 已返回列信息，直接使用。SQL 自动追加 LIMIT。结果自动保存到分析状态。")
    public String runDuckdb(
            @ToolParam(description = "SELECT 查询语句。表名用 loadData 返回的 viewName，支持 JOIN") String sql,
            @ToolParam(description = "结果引用的名称（后续步骤用此名称引用数据），如 monthly_sales") String outputKey) {
        // 意图守卫
        String guardResult = checkIntentGuard();
        if (guardResult != null) return guardResult;

        try {
            List<AnalysisState.LoadedFileRecord> files = analysisState.getLoadedFiles();
            if (files == null || files.isEmpty()) {
                return ToolResultUtils.jsonTypedError("syntax", "没有已加载的数据文件，请先调用 loadData");
            }

            // 回合内去重：同一 outputKey 已有结果时直接返回
            String existing = analysisState.getDataByKey(outputKey);
            if (existing != null) {
                JSONArray existingData = JSON.parseArray(existing);
                int existingCount = existingData != null ? existingData.size() : 0;
                analysisState.addStepResult(outputKey, "runDuckdb", existingCount, sql);
                JSONObject summary = new JSONObject();
                summary.put("outputKey", outputKey);
                summary.put("rows", existingCount);
                summary.put("cached", true);
                summary.put("sample", existingData != null && existingData.size() > 0
                        ? existingData.subList(0, Math.min(3, existingData.size())) : JSONArray.of());
                log.info("runDuckdb: 命中缓存 outputKey={}, rows={}", outputKey, existingCount);
                return summary.toJSONString();
            }

            // 构建 FileRef 列表
            List<DuckDbConfig.FileRef> refs = files.stream()
                    .map(f -> new DuckDbConfig.FileRef(f.parquetPath, f.viewName))
                    .collect(Collectors.toList());

            // 跨轮次 SQL 缓存（输出键不同但 SQL 可能相同）
            String cacheKey = buildCacheKey(refs, sql);
            String cachedResult = sqlCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                JSONArray cachedData = JSON.parseArray(cachedResult);
                int cachedCount = cachedData != null ? cachedData.size() : 0;
                analysisState.addStepResult(outputKey, "runDuckdb", cachedCount, sql);
                analysisState.addData(outputKey, cachedResult);
                log.info("runDuckdb: Caffeine 缓存命中, outputKey={}, rows={}, sql={}",
                        outputKey, cachedCount, truncateSql(sql));
                JSONObject summary = new JSONObject();
                summary.put("outputKey", outputKey);
                summary.put("rows", cachedCount);
                summary.put("cached", true);
                summary.put("sample", cachedData != null && cachedData.size() > 0
                        ? cachedData.subList(0, Math.min(3, cachedData.size())) : JSONArray.of());
                return summary.toJSONString();
            }

            String conversationId = analysisState.getCurrentThreadId();
            String result = duckDbQueryService.executeQuery(conversationId, refs, sql);

            // 瞬态错误自动重试一次（system/timeout）
            if (result != null && ToolResultUtils.isTransientError(result)) {
                log.warn("runDuckdb: 瞬态错误，重试一次: sql={}", sql);
                result = duckDbQueryService.executeQuery(conversationId, refs, sql);
            }

            if (result != null && result.contains("\"error\"")) {
                return result;
            }

            // 存入 Caffeine 缓存（跨轮次复用）
            sqlCache.put(cacheKey, result);

            // 存入分析状态
            JSONArray data = JSON.parseArray(result);
            int rowCount = data != null ? data.size() : 0;
            analysisState.addStepResult(outputKey, "runDuckdb", rowCount, sql);
            analysisState.addData(outputKey, result);

            JSONObject summary = new JSONObject();
            summary.put("outputKey", outputKey);
            summary.put("rows", rowCount);
            summary.put("sample", data != null && data.size() > 0
                    ? data.subList(0, Math.min(3, data.size())) : JSONArray.of());

            log.info("runDuckdb: cid={}, outputKey={}, rows={}, {} 个视图",
                    analysisState.getCurrentThreadId(), outputKey, rowCount, files.size());
            return summary.toJSONString();
        } catch (Exception e) {
            log.error("runDuckdb 失败: sql={}", sql, e);
            analysisState.addStepResultFailed(outputKey, "runDuckdb", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "查询异常: " + e.getMessage());
        }
    }

    /**
     * 批量数值统计（快速获取多列的 COUNT/AVG/MIN/MAX/STDDEV/PERCENTILE）。
     * <p>
     * 在所有已加载文件的第一个文件上执行（如需指定文件，请用 runDuckdb 手写 SQL）。
     */
    @Tool(description = "批量计算多列的统计量（COUNT, AVG, MIN, MAX, STDDEV, PERCENTILE_25, PERCENTILE_50, PERCENTILE_75）。" +
            "✅ 快速了解数据分布 ❌ 不适合排名/趋势（用 runDuckdb + SQL 聚合函数）")
    public String queryStatistics(
            @ToolParam(description = "要统计的数值列名，逗号分隔，如 sales,profit,amount") String columns) {
        // 意图守卫
        String guardResult = checkIntentGuard();
        if (guardResult != null) return guardResult;

        String statsKey = columns + "_stats";
        try {
            List<AnalysisState.LoadedFileRecord> files = analysisState.getLoadedFiles();
            if (files == null || files.isEmpty()) return ToolResultUtils.jsonTypedError("syntax", "没有已加载的数据文件");

            // 回合内去重
            String existing = analysisState.getDataByKey(statsKey);
            if (existing != null) {
                log.info("queryStatistics: 命中缓存 columns={}", columns);
                return existing;
            }

            // 在当前架构下，统计默认针对第一个文件。如需跨文件统计，Agent 应使用 runDuckdb。
            AnalysisState.LoadedFileRecord activeFile = files.get(files.size() - 1);

            String[] cols = columns.split(",");
            StringBuilder sqlBuilder = new StringBuilder("SELECT");
            for (int i = 0; i < cols.length; i++) {
                String col = cols[i].trim();
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
            String cachedResult = sqlCache.getIfPresent(cacheKey);
            if (cachedResult != null) {
                log.info("queryStatistics: Caffeine 缓存命中, columns={}", columns);
                analysisState.addData(statsKey, cachedResult);
                analysisState.addStepResult(statsKey, "queryStatistics",
                        JSON.parseArray(cachedResult).size(), null);
                sqlCache.put(cacheKey, cachedResult);
                return cachedResult;
            }
            String conversationId = analysisState.getCurrentThreadId();
            String result = duckDbQueryService.executeQuery(conversationId, refs, querySql);

            // 瞬态错误自动重试一次
            if (result != null && ToolResultUtils.isTransientError(result)) {
                log.warn("queryStatistics: 瞬态错误，重试一次: columns={}", columns);
                result = duckDbQueryService.executeQuery(conversationId, refs, sqlBuilder.toString());
            }

            if (result != null && result.contains("\"error\"")) return result;

            sqlCache.put(cacheKey, result);
            analysisState.addData(statsKey, result);
            analysisState.addStepResult(statsKey, "queryStatistics",
                    JSON.parseArray(result).size(), null);

            return result;
        } catch (Exception e) {
            log.error("queryStatistics 失败", e);
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

    /**
     * 截断 SQL 日志输出（过长时保留前 200 字符）。
     */
    private static String truncateSql(String sql) {
        return sql != null && sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }

}
