package com.AIBI.AgentTool;

import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.DataFile;
import com.AIBI.service.FileConversionService;
import com.AIBI.utils.ToolResultUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.AIBI.agent.model.AnalysisState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据加载工具：供 ExecutorAgent 使用。
 * <p>
 * 按前端指定的文件 ID 加载数据。如本轮消息无指定文件，则复用已有加载结果。
 * 文件切换时旧文件不会污染新查询，每次 loadData 输出明确的数据范围。
 */
@Slf4j
@Component
public class DataLoadingTool {

    @Autowired
    private DataFileMapper dataFileMapper;

    @Autowired
    private FileConversionService fileConversionService;

    @Autowired
    private AnalysisState analysisState;

    // ─── 意图声明守卫 ─────────────────────────────────────

    /**
     * 意图声明 — Agent 回复用户前必须先调用此工具声明意图。
     * <p>
     * 三层架构 Layer 2 核心：通过 Tool Calling 强制 LLM 输出结构化意图参数，
     * 后续工具根据意图决定是否放行。
     */
    @Tool(description = "【必选】每次回复用户前，必须先调用此工具声明对用户意图的判断。" +
            "之后才能调用其他工具或回复。不调用此工具视为违规。")
    public String declareIntent(
            @ToolParam(description = "意图分类：analysis(数据分析，用户指定了维度或指标)/" +
                    "chitchat(日常对话、问候、闲聊)/" +
                    "vague(有分析倾向但维度和指标不明确，需追问)") String category,
            @ToolParam(description = "分析维度（category=analysis时填写），如 [\"产品\",\"地区\",\"月份\"]。" +
                    "category=chitchat或vague时传空数组") List<String> dimensions,
            @ToolParam(description = "分析指标（category=analysis时填写），如 [\"销售额\",\"利润\",\"数量\"]。" +
                    "category=chitchat或vague时传空数组") List<String> metrics,
            @ToolParam(description = "清晰度：clear(维度和指标明确)/somewhat(有方向但不完整)/vague(模糊)") String clarity,
            @ToolParam(description = "一句话总结用户想要什么") String summary) {

        if (category == null) category = "vague";
        if (dimensions == null) dimensions = List.of();
        if (metrics == null) metrics = List.of();

        analysisState.setIntent(category, dimensions, metrics, clarity, summary);
        log.info("declareIntent: category={}, clarity={}, dims={}, metrics={}, summary={}",
                category, clarity, dimensions, metrics, summary);

        return "意图已记录：类别=" + category + "，清晰度=" + clarity
                + (dimensions.isEmpty() ? "" : "，维度=" + dimensions)
                + (metrics.isEmpty() ? "" : "，指标=" + metrics);
    }

    /**
     * 意图守卫 — 在 loadData 执行前检查 intent 是否为 analysis。
     */
    private String checkIntentGuard() {
        String category = analysisState.getIntentCategory();
        if (category == null) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "请先调用 declareIntent 声明意图后再加载数据。");
        }
        if ("chitchat".equals(category)) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "当前为日常对话模式，无需加载数据文件。请直接回复用户。");
        }
        if ("vague".equals(category)) {
            return ToolResultUtils.jsonTypedError("syntax",
                    "分析目标不明确，请先向用户确认想分析哪些维度和指标。");
        }
        return null; // 放行
    }

    /**
     * 加载本轮消息绑定的数据文件到分析环境。
     * <p>
     * 根据前端传入的 fileIds 加载对应文件。无 fileIds 时复用已有加载结果。
     * 每个文件有独立的 viewName，SQL 中通过 viewName 引用。
     */
    @Tool(description = "加载数据文件到分析环境，返回所有已加载文件的表结构和 viewName。" +
            "✅ 首次分析前必须调用一次 ❌ 无文件时说明未绑定数据，请提示用户上传文件。" +
            "该方法只加载本轮消息指定的文件，不会看到历史消息中的文件。" +
            "每个文件有独立的 viewName，SQL 中通过 viewName 引用。")
    public String loadData() {
        // 意图守卫
        String guardResult = checkIntentGuard();
        if (guardResult != null) return guardResult;

        String conversationId = analysisState.getCurrentThreadId();
        if (conversationId == null) return ToolResultUtils.jsonTypedError("system", "分析状态未初始化");

        try {
            // 1. 优先检查本轮 active 文件 ID
            List<Long> activeIds = analysisState.getActiveFileIds();
            List<DataFile> files;

            if (activeIds != null && !activeIds.isEmpty()) {
                // 按前端指定的文件加载
                QueryWrapper<DataFile> qw = new QueryWrapper<>();
                qw.in("id", activeIds).eq("status", "READY");
                files = dataFileMapper.selectList(qw);
                log.info("loadData: 按 activeFileIds 加载 {} 个文件: {}", files.size(), activeIds);
            } else {
                // 2. 无 activeFileIds → 检查已有 loadedFiles（附言时复用）
                List<AnalysisState.LoadedFileRecord> existing = analysisState.getLoadedFiles();
                if (existing != null && !existing.isEmpty()) {
                    return buildExistingResult(existing);
                }
                // 3. 从未加载过 → 返回空
                analysisState.setLoadedFiles(new ArrayList<>());
                return ToolResultUtils.jsonTypedError("syntax", "未指定数据文件。上传文件后，请在输入区域将文件附加到消息中再发送。");
            }

            if (files.isEmpty()) {
                analysisState.setLoadedFiles(new ArrayList<>());
                return ToolResultUtils.jsonTypedError("syntax", "指定的文件未找到或状态异常，请重新上传");
            }

            List<AnalysisState.LoadedFileRecord> records = new ArrayList<>();
            JSONArray fileInfos = new JSONArray();

            for (DataFile df : files) {
                FileConversionService.SchemaInfo schema = fileConversionService.getSchema(
                        df.getViewName(), df.getStoragePath());

                AnalysisState.LoadedFileRecord record = new AnalysisState.LoadedFileRecord();
                record.fileId = df.getId().toString();
                record.originalFilename = df.getOriginalFilename();
                record.viewName = df.getViewName();
                record.parquetPath = df.getStoragePath();
                record.rowCount = schema.rowCount;

                List<AnalysisState.ColumnRecord> cols = new ArrayList<>();
                JSONArray colsJson = new JSONArray();
                for (var col : schema.columns) {
                    cols.add(new AnalysisState.ColumnRecord(col.name, col.type, col.samples));
                    JSONObject cj = new JSONObject();
                    cj.put("name", col.name);
                    cj.put("type", col.type);
                    cj.put("samples", col.samples != null ? col.samples : JSONArray.of());
                    colsJson.add(cj);
                }
                record.columns = cols;
                records.add(record);

                JSONObject fi = new JSONObject();
                fi.put("fileId", df.getId());
                fi.put("filename", df.getOriginalFilename());
                fi.put("viewName", df.getViewName());
                fi.put("rowCount", schema.rowCount);
                fi.put("columns", colsJson);
                fileInfos.add(fi);
            }

            // 注册到 AnalysisState
            analysisState.setLoadedFiles(records);

            JSONObject result = new JSONObject();
            result.put("fileCount", files.size());
            result.put("files", fileInfos);
            result.put("note", "以上是本次查询可用的文件。SQL 中通过 viewName 引用文件。");

            log.info("loadData: conversationId={}, {} 个文件已加载 (activeFileIds={})",
                    conversationId, files.size(), analysisState.getActiveFileIds());
            return result.toJSONString();
        } catch (Exception e) {
            log.error("loadData 失败: conversationId={}", conversationId, e);
            analysisState.addStepResultFailed("loadData", "loadData", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "文件加载失败: " + e.getMessage() + "。请确认文件状态后重试或重新上传。");
        }
    }

    /**
     * 返回已有加载结果的摘要（复用模式）。
     */
    private String buildExistingResult(List<AnalysisState.LoadedFileRecord> existing) {
        JSONArray fileInfos = new JSONArray();
        for (var record : existing) {
            JSONObject fi = new JSONObject();
            fi.put("fileId", record.fileId);
            fi.put("filename", record.originalFilename);
            fi.put("viewName", record.viewName);
            fi.put("rowCount", record.rowCount);
            JSONArray colsJson = new JSONArray();
            if (record.columns != null) {
                for (var col : record.columns) {
                    JSONObject cj = new JSONObject();
                    cj.put("name", col.name);
                    cj.put("type", col.type);
                    cj.put("samples", col.samples != null ? col.samples : JSONArray.of());
                    colsJson.add(cj);
                }
            }
            fi.put("columns", colsJson);
            fileInfos.add(fi);
        }
        JSONObject result = new JSONObject();
        result.put("fileCount", existing.size());
        result.put("files", fileInfos);
        result.put("note", "沿用已有数据文件。如需切换文件，请发送新消息并附加其他文件。");
        return result.toJSONString();
    }

    /**
     * 获取指定文件的列 schema 信息（含采样值，供 LLM 理解数据结构）。
     * <p>
     * 可通过 fileId 或 viewName 来定位文件。仅返回本轮 active 文件的 schema。
     */
    @Tool(description = "获取已加载数据文件的列名、类型和采样值。" +
            "✅ 编写 SQL 前用此工具确认列名 ❌ 不要用 runPython 来查看数据。" +
            "fileRef 可以是 fileId 或 viewName（来自 loadData 的返回值）。" +
            "仅可查询本轮已加载的文件。")
    public String getSchema(
            @ToolParam(description = "文件 ID 或 viewName（来自 loadData 返回值）") String fileRef) {
        try {
            DataFile df;
            try {
                df = dataFileMapper.selectById(Long.valueOf(fileRef));
            } catch (NumberFormatException e) {
                QueryWrapper<DataFile> qw = new QueryWrapper<>();
                qw.eq("viewName", fileRef);
                df = dataFileMapper.selectOne(qw);
            }

            if (df == null) return ToolResultUtils.jsonTypedError("syntax", "文件不存在: " + fileRef);

            // 校验该文件是否在 active 范围内
            final String targetFileId = df.getId().toString();
            List<AnalysisState.LoadedFileRecord> loaded = analysisState.getLoadedFiles();
            boolean isActive = loaded.stream().anyMatch(r -> r.fileId.equals(targetFileId));
            if (!isActive) {
                return ToolResultUtils.jsonTypedError("syntax", "文件 \"" + df.getOriginalFilename() + "\" 不在当前查询范围。请重新 attach 该文件后发送消息。");
            }

            FileConversionService.SchemaInfo schema = fileConversionService.getSchema(
                    df.getViewName(), df.getStoragePath());

            JSONArray cols = new JSONArray();
            for (var col : schema.columns) {
                JSONObject c = new JSONObject();
                c.put("name", col.name);
                c.put("type", col.type);
                c.put("samples", col.samples != null ? col.samples : JSONArray.of());
                cols.add(c);
            }
            JSONObject result = new JSONObject();
            result.put("fileId", df.getId());
            result.put("filename", df.getOriginalFilename());
            result.put("viewName", df.getViewName());
            result.put("rowCount", schema.rowCount);
            result.put("columns", cols);
            return result.toJSONString();
        } catch (Exception e) {
            log.error("getSchema 失败: fileRef={}", fileRef, e);
            analysisState.addStepResultFailed(fileRef, "getSchema", e.getMessage());
            return ToolResultUtils.jsonTypedError("syntax", "获取 schema 失败: " + e.getMessage() + "。请确认 viewName 或 fileId 是否正确。");
        }
    }

    /**
     * 查看当前对话的分析进度。
     */
    @Tool(description = "查看当前对话已有的数据文件、已执行的步骤、可用的数据产出物。" +
            "✅ 不确定当前状态时调用 ❌ 不要重复调用（状态会自动注入到上下文）")
    public String getAnalysisState() {
        try {
            return analysisState.toContextString();
        } catch (Exception e) {
            return "分析状态获取失败: " + e.getMessage();
        }
    }

}
