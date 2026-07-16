package com.AIBI.config;

import com.AIBI.agent.model.AnalysisState;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AnalysisState Redis 持久化层。
 * <p>
 * 在每轮对话结束时将 AnalysisState 的关键数据持久化到 Redis，
 * 下一轮开始时恢复，使 Agent 能跨轮复用已加载文件、执行步骤和 SQL 结果。
 * <p>
 * 数据拆分为两个 Redis Hash：
 * <ul>
 *   <li>{@code analysis:state:meta:{conversationId}} — 元数据（文件、步骤、意图），TTL=3天</li>
 *   <li>{@code analysis:state:data:{conversationId}} — SQL 结果（大体积），TTL=1小时</li>
 * </ul>
 * <p>
 * 降级策略：Redis 不可用时静默降级，AnalysisState 退化为纯内存模式。
 */
@Slf4j
@Component
public class AnalysisStateStore {

    private static final String META_KEY_PREFIX = "analysis:state:meta:";
    private static final String DATA_KEY_PREFIX = "analysis:state:data:";

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Autowired
    private AnalysisStateStoreProperties properties;

    private boolean available;

    @PostConstruct
    public void init() {
        available = properties.isEnabled() && redissonClient != null;
        if (available) {
            log.info("状态持久化已启用：metaTTL={}d、dataTTL={}m、dataCapacity={}KB",
                    properties.getMetaTtlDays(), properties.getDataTtlMinutes(),
                    properties.getMaxDataTotalBytes() / 1024);
        } else {
            log.info("状态持久化已禁用");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 保存指定 conversation 的 AnalysisState 到 Redis。
     * <p>
     * 元数据（文件、步骤、意图）→ meta Hash，TTL=3天。
     * SQL 结果（dataIndex）→ data Hash，TTL=1小时，单条超限不存。
     */
    public void save(String conversationId, AnalysisState state) {
        if (!available || conversationId == null) return;

        try {
            // ── 1. 保存 meta ──────────────────────────────
            RMap<String, String> meta = redissonClient.getMap(META_KEY_PREFIX + conversationId);

            // loadedFiles
            List<AnalysisState.LoadedFileRecord> files = state.getLoadedFiles();
            meta.put("loadedFiles", files.isEmpty() ? "[]" : JSON.toJSONString(files));

            // stepResults
            List<AnalysisState.StepRecord> steps = state.getSteps();
            meta.put("stepResults", steps.isEmpty() ? "[]" : JSON.toJSONString(steps));

            Map<String, AnalysisState.QueryOutputRecord> queryOutputs = state.getQueryOutputs();
            meta.put("queryOutputs", queryOutputs.isEmpty() ? "{}" : JSON.toJSONString(queryOutputs));

            // activeFileIds
            List<Long> activeIds = state.getActiveFileIds();
            meta.put("activeFileIds", activeIds.isEmpty() ? "[]" : JSON.toJSONString(activeIds));

            // intent 已迁移至 RunContext（请求级，不跨轮持久化），不再保存到 Redis
            meta.put("intent", "{}");

            meta.put("updatedAt", java.time.Instant.now().toString());

            // 设置 meta TTL
            Duration metaTtl = Duration.ofDays(properties.getMetaTtlDays());
            meta.expire(metaTtl);

            // ── 2. 保存 dataIndex（SQL 结果）─────────────
            saveDataIndex(conversationId, state);

            log.debug("状态已保存：文件={}、步骤={}、查询索引={}、数据键数={}",
                    files.size(), steps.size(), queryOutputs.size(), state.getAvailableKeys().size());
        } catch (Exception e) {
            log.warn("状态保存失败，降级为纯内存模式", e);
        }
    }

    /**
     * 从 Redis 恢复指定 conversation 的 AnalysisState 快照。
     * <p>
     * 返回恢复必要数据的映射，调用方通过 {@link AnalysisState#restore(String, List, List, Map, List)} 应用。
     *
     * @return 快照封装，Redis 中无数据时返回 null
     */
    public StateSnapshot load(String conversationId) {
        if (!available || conversationId == null) return null;

        try {
            RMap<String, String> meta = redissonClient.getMap(META_KEY_PREFIX + conversationId);

            // 检查是否存在（loadedFiles 是必填字段）
            String filesJson = meta.get("loadedFiles");
            if (filesJson == null || filesJson.isEmpty() || "[]".equals(filesJson)) {
                return null;
            }

            // ── 恢复 meta ──────────────────────────────
            List<AnalysisState.LoadedFileRecord> files = JSON.parseArray(
                    filesJson, AnalysisState.LoadedFileRecord.class);
            if (files == null) files = Collections.emptyList();

            List<AnalysisState.StepRecord> steps = Collections.emptyList();
            String stepsJson = meta.get("stepResults");
            if (stepsJson != null && !stepsJson.isEmpty() && !"[]".equals(stepsJson)) {
                steps = JSON.parseArray(stepsJson, AnalysisState.StepRecord.class);
            }
            if (steps == null) steps = Collections.emptyList();

            Map<String, AnalysisState.QueryOutputRecord> queryOutputs = Collections.emptyMap();
            String queryOutputsJson = meta.get("queryOutputs");
            if (queryOutputsJson != null && !queryOutputsJson.isEmpty() && !"{}".equals(queryOutputsJson)) {
                queryOutputs = JSON.parseObject(queryOutputsJson,
                        new com.alibaba.fastjson2.TypeReference<LinkedHashMap<String, AnalysisState.QueryOutputRecord>>() {});
            }
            if (queryOutputs == null) queryOutputs = Collections.emptyMap();

            List<Long> activeIds = Collections.emptyList();
            String activeIdsJson = meta.get("activeFileIds");
            if (activeIdsJson != null && !activeIdsJson.isEmpty() && !"[]".equals(activeIdsJson)) {
                activeIds = JSON.parseArray(activeIdsJson, Long.class);
            }
            if (activeIds == null) activeIds = Collections.emptyList();

            // ── 恢复 dataIndex ──────────────────────────
            RMap<String, String> data = redissonClient.getMap(DATA_KEY_PREFIX + conversationId);
            Map<String, String> rawDataIndex = data.readAllMap();
            Map<String, String> dataIndex = restoreDataIndexOrder(rawDataIndex, meta.get("dataIndexOrder"));

            // 标记被截断的条目
            String truncatedStr = meta.get("dataTruncated");
            List<String> truncated = truncatedStr != null && !truncatedStr.isEmpty()
                    ? JSON.parseArray(truncatedStr, String.class)
                    : Collections.emptyList();
            if (!truncated.isEmpty()) {
                log.warn("以下键因体积过大未持久化：{}", truncated);
            }

            log.info("状态已恢复：文件={}、步骤={}、查询索引={}、数据键数={}",
                    files.size(), steps.size(), queryOutputs.size(), dataIndex.size());

            return new StateSnapshot(files, steps, dataIndex, queryOutputs, activeIds);
        } catch (Exception e) {
            log.warn("状态恢复失败，从头开始", e);
            return null;
        }
    }

    /**
     * 物理删除指定 conversation 在 Redis 中的所有状态数据。
     * <p>
     * 在 {@code resetConversation} 时调用，确保对话重置后状态完全清除。
     */
    public void delete(String conversationId) {
        if (!available || conversationId == null) return;

        try {
            redissonClient.getMap(META_KEY_PREFIX + conversationId).delete();
            redissonClient.getMap(DATA_KEY_PREFIX + conversationId).delete();
            log.info("状态数据已删除");
        } catch (Exception e) {
            log.warn("状态数据删除失败", e);
        }
    }

    /**
     * 检查指定 conversation 是否有持久化的状态。
     */
    public boolean exists(String conversationId) {
        if (!available || conversationId == null) return false;
        try {
            return redissonClient.getMap(META_KEY_PREFIX + conversationId).isExists();
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 持久化 dataIndex 到 Redis data Hash。
     * <p>
     * 限制：
     * <ul>
     *   <li>单条超过 {@code maxDataEntryBytes} 的跳过（在 meta 中记录 key）</li>
     *   <li>总容量超过 {@code maxDataTotalBytes} 时淘汰最早结果</li>
     * </ul>
     */
    private void saveDataIndex(String conversationId, AnalysisState state) {
        Map<String, String> dataMap = new LinkedHashMap<>();
        List<String> truncatedKeys = new ArrayList<>();

        for (String key : state.getAvailableKeys()) {
            String value = state.getDataByKey(key);
            if (value == null) continue;

            int byteSize = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (byteSize > properties.getMaxDataEntryBytes()) {
                truncatedKeys.add(key);
                log.warn("条目超限不持久化：键={}、大小={}、上限={}", key, byteSize, properties.getMaxDataEntryBytes());
                continue;
            }
            dataMap.put(key, value);
        }

        trimDataIndexToCapacity(dataMap, properties.getMaxDataTotalBytes());

        // ── 写入 Redis ─────────────────────────────────
        RMap<String, String> data = redissonClient.getMap(DATA_KEY_PREFIX + conversationId);
        data.clear();
        if (!dataMap.isEmpty()) {
            data.putAll(dataMap);
        }
        Duration dataTtl = Duration.ofMinutes(properties.getDataTtlMinutes());
        data.expire(dataTtl);

        // ── 记录截断 key ─────────────────────────────
        RMap<String, String> meta = redissonClient.getMap(META_KEY_PREFIX + conversationId);
        meta.put("dataTruncated", JSON.toJSONString(truncatedKeys));
        meta.put("dataIndexOrder", JSON.toJSONString(new ArrayList<>(dataMap.keySet())));
    }

    /** 按总字节数控制 Redis 占用；优先保留最新查询结果。 */
    static void trimDataIndexToCapacity(Map<String, String> dataMap, int maxBytes) {
        if (maxBytes <= 0 || dataMap.isEmpty()) return;
        int totalBytes = dataMap.values().stream()
                .mapToInt(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();
        if (totalBytes <= maxBytes) return;

        int removed = 0;
        Iterator<Map.Entry<String, String>> iterator = dataMap.entrySet().iterator();
        while (totalBytes > maxBytes && iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            totalBytes -= entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            iterator.remove();
            removed++;
        }
        log.warn("数据索引容量超限：淘汰{}条，保留={}KB，上限={}KB", removed,
                totalBytes / 1024, maxBytes / 1024);
    }

    /** Redis Hash 不保证遍历顺序，单独保存键顺序以保证淘汰策略跨轮一致。 */
    private static Map<String, String> restoreDataIndexOrder(Map<String, String> rawDataIndex,
                                                              String orderJson) {
        Map<String, String> ordered = new LinkedHashMap<>();
        if (rawDataIndex == null || rawDataIndex.isEmpty()) return ordered;
        List<String> order = Collections.emptyList();
        if (orderJson != null && !orderJson.isBlank()) {
            try {
                List<String> parsed = JSON.parseArray(orderJson, String.class);
                order = parsed != null ? parsed : Collections.emptyList();
            } catch (Exception ignored) {
                // 旧格式或损坏元数据不影响状态恢复，退化为 Redis 当前遍历顺序。
            }
        }
        if (order != null) {
            for (String key : order) {
                String value = rawDataIndex.get(key);
                if (value != null) ordered.put(key, value);
            }
        }
        rawDataIndex.forEach(ordered::putIfAbsent);
        return ordered;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部类
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 Redis 恢复的状态快照。
     * <p>
     * 仅为数据传输对象，不包含 intent 和 chartOptions（每轮重新生成）。
     */
    public record StateSnapshot(
            List<AnalysisState.LoadedFileRecord> files,
            List<AnalysisState.StepRecord> steps,
            Map<String, String> dataIndex,
            Map<String, AnalysisState.QueryOutputRecord> queryOutputs,
            List<Long> activeFileIds
    ) {
        public boolean isEmpty() {
            return (files == null || files.isEmpty())
                    && (steps == null || steps.isEmpty())
                    && (dataIndex == null || dataIndex.isEmpty())
                    && (queryOutputs == null || queryOutputs.isEmpty())
                    && (activeFileIds == null || activeFileIds.isEmpty());
        }
    }
}
