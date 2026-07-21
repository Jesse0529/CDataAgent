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
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    @Tool(description = "每轮首调：声明意图。仅 category=analysis 时可调用数据工具。")
    public String declareIntent(
            @ToolParam(description = "analysis/vague/chitchat") String category,
            @ToolParam(description = "分析维度；非 analysis 传 []") List<String> dimensions,
            @ToolParam(description = "分析指标；非 analysis 传 []") List<String> metrics,
            @ToolParam(description = "clear/somewhat/vague") String clarity,
            @ToolParam(description = "用户目标摘要") String summary,
            @ToolParam(description = "table/chart/text；未指定传 []") List<String> outputFormats) {

        if (category == null) category = "vague";
        if (dimensions == null) dimensions = List.of();
        if (metrics == null) metrics = List.of();
        if (outputFormats == null) outputFormats = List.of();

        // 意图声明写入 RunContext（替代 AnalysisState.setIntent）
        RunContext ctx = RunContextHolder.require();
        ctx.setIntent(category, dimensions, metrics, clarity, summary, outputFormats);
        log.info("意图声明：分类={}、清晰度={}、维度={}、指标={}",
                category, clarity, dimensions, metrics);

        return "意图已记录：类别=" + category + "，清晰度=" + clarity
                + (dimensions.isEmpty() ? "" : "，维度=" + dimensions)
                + (metrics.isEmpty() ? "" : "，指标=" + metrics)
                + (outputFormats.isEmpty() ? "" : "，输出格式=" + outputFormats);
    }

    /**
     * 加载本轮消息绑定的数据文件到分析环境。
     * <p>
     * 根据本轮显式文件范围加载对应文件。旧客户端未声明范围时才兼容复用已有结果。
     * 每个文件有独立的 viewName，SQL 中通过 viewName 引用。
     */
    @Tool(description = "加载本轮数据文件，返回 viewName、列名和类型；需要样本时再调用 getSchema。")
    public String loadData() {
        RunContext context = RunContextHolder.require();
        String conversationId = context.getConversationId().toString();

        try {
            // 1. 显式范围只认当前请求的文件 ID，绝不沿用历史文件。
            List<Long> activeIds = new ArrayList<>(context.getFileScopeIds());
            List<DataFile> files;

            if (context.isExplicitFileScope()) {
                if (activeIds.isEmpty()) {
                    return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                            "本轮未附加数据文件。需要分析数据时，请在输入区域选择文件后发送。");
                }
                QueryWrapper<DataFile> qw = new QueryWrapper<>();
                qw.in("id", activeIds)
                        .eq("conversationId", conversationId)
                        .eq("status", "READY");
                files = dataFileMapper.selectList(qw);
            } else if (!analysisState.getActiveFileIds().isEmpty()) {
                // 旧调用方兼容：仍按 activeFileIds 加载。
                QueryWrapper<DataFile> qw = new QueryWrapper<>();
                qw.in("id", analysisState.getActiveFileIds()).eq("status", "READY");
                files = dataFileMapper.selectList(qw);
            } else {
                // 2. 旧调用方未传范围时，兼容复用已有加载结果。
                List<AnalysisState.LoadedFileRecord> existing = analysisState.getLoadedFiles();
                if (existing != null && !existing.isEmpty()) {
                    return buildExistingResult(existing);
                }
                // 3. 从未加载过 → 返回空
                analysisState.setLoadedFiles(new ArrayList<>());
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "未指定数据文件。上传文件后，请在输入区域将文件附加到消息中再发送。");
            }

            if (files.isEmpty()) {
                analysisState.setLoadedFiles(new ArrayList<>());
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "本轮绑定的文件不存在、已删除或状态异常，请重新选择文件后发送");
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
            context.markFileScopeLoaded();

            JSONObject result = new JSONObject();
            result.put("fileCount", files.size());
            result.put("files", fileInfos);
            result.put("note", "以上是本次查询可用的文件。需要查看某个文件的样本值时，再用 getSchema；SQL 中通过 viewName 引用文件。");

            List<String> names = files.stream()
                    .map(DataFile::getOriginalFilename)
                    .collect(Collectors.toList());
            log.info("数据加载：{}个文件已加载：{}", files.size(), names);
            return result.toJSONString();
        } catch (Exception e) {
            log.error("数据加载失败", e);
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
    @Tool(description = "读取本轮已附加或已加载文件的列名、类型和样本；可在 loadData 前预览 schema，但执行 SQL 前必须先调用 loadData。")
    public String getSchema(
            @ToolParam(description = "loadData 返回的 fileId 或 viewName") String fileRef) {
        try {
            DataFile df = findFile(fileRef);

            if (df == null) return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                    "文件不存在: " + fileRef);
            if (!"READY".equals(df.getStatus())) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "文件尚未就绪: " + df.getOriginalFilename());
            }

            final String targetFileId = df.getId().toString();
            RunContext context = RunContextHolder.require();
            if (context.isExplicitFileScope()
                    && (!context.allowsFile(df.getId())
                    || !context.getConversationId().equals(df.getConversationId()))) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "文件 \"" + df.getOriginalFilename() + "\" 不在当前查询范围。请重新 attach 该文件后发送消息。");
            }
            List<AnalysisState.LoadedFileRecord> loaded = analysisState.getLoadedFiles();
            boolean isLoaded = loaded.stream().anyMatch(r -> targetFileId.equals(r.fileId));
            boolean isAttached = context.isExplicitFileScope()
                    ? context.allowsFile(df.getId())
                    : analysisState.getActiveFileIds().contains(df.getId());
            if (!isLoaded && !isAttached) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "文件 \"" + df.getOriginalFilename() + "\" 不在当前查询范围。请重新 attach 该文件后发送消息。");
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
            result.put("loaded", isLoaded);
            if (!isLoaded) {
                result.put("note", "这是本轮附加文件的 schema 预览；执行 SQL 前请先调用 loadData。");
            }
            return result.toJSONString();
        } catch (Exception e) {
            log.error("获取表结构失败：文件引用={}", fileRef, e);
            analysisState.addStepResultFailed(fileRef, "getSchema", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "获取 schema 失败: " + e.getMessage() + "。请确认文件状态后重试。");
        }
    }

    private DataFile findFile(String fileRef) {
        if (fileRef == null || fileRef.isBlank()) return null;
        try {
            return dataFileMapper.selectById(Long.valueOf(fileRef));
        } catch (NumberFormatException ignored) {
            QueryWrapper<DataFile> qw = new QueryWrapper<>();
            qw.eq("viewName", fileRef);
            return dataFileMapper.selectOne(qw);
        }
    }

    /**
     * 查看当前对话的分析进度。
     */
    @Tool(description = "查看已加载文件、执行步骤和可用 outputKey；仅状态不确定时调用。")
    public String getAnalysisState() {
        try {
            return analysisState.toContextString();
        } catch (Exception e) {
            return "分析状态获取失败: " + e.getMessage();
        }
    }

}
