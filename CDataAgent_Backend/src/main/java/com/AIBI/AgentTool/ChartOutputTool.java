package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.config.DuckDbConfig;
import com.AIBI.service.DuckDbQueryService;
import com.AIBI.utils.ToolCacheManager;
import com.AIBI.utils.ToolResultUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.icepear.echarts.*;
import org.icepear.echarts.charts.line.LineAreaStyle;
import org.icepear.echarts.charts.line.LineSeries;
import org.icepear.echarts.charts.pie.PieSeries;
import org.icepear.echarts.render.Engine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 图表输出工具：供 SynthesizerAgent 使用。
 * 使用 echarts-java 确定性构建 ECharts v5 option JSON。
 */
@Slf4j
@Component
public class ChartOutputTool {

    @Autowired
    private ToolCacheManager cacheManager;

    @Autowired
    private AnalysisState analysisState;

    @Autowired
    private DuckDbQueryService duckDbQueryService;

    private static final int MAX_DIM_VALUES = 80;

    private static final int DATA_SCHEMA_SAMPLE_ROWS = 3;

    /**
     * 返回指定分析结果的真实字段名与样本，供 Synthesizer 在构建图表前选择字段。
     */
    @Tool(description = "读取 dataRef 的真实字段和样本；buildChart 前必调，字段名必须原样使用。")
    public String describeData(
            @ToolParam(description = "runDuckdb 或 runPython 返回的 outputKey") String dataRef) {
        try {
            String truncatedError = truncatedResultError(dataRef);
            if (truncatedError != null) return truncatedError;
            JSONArray dataArray = getDataArray(dataRef);
            if (dataArray == null) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION, "数据引用不存在: " + dataRef
                        + "。可用数据: " + analysisState.getAvailableKeys());
            }
            if (dataArray.isEmpty()) return ToolResultUtils.jsonTypedError("schema", "数据为空: " + dataRef);

            List<String> fields = availableFields(dataArray);
            JSONArray fieldDetails = new JSONArray();
            JSONObject firstRow = dataArray.getJSONObject(0);
            for (String field : fields) {
                JSONObject detail = new JSONObject();
                Object value = firstRow.get(field);
                detail.put("name", field);
                detail.put("type", value == null ? "null" : value.getClass().getSimpleName());
                fieldDetails.add(detail);
            }

            JSONArray samples = new JSONArray();
            for (int i = 0; i < Math.min(DATA_SCHEMA_SAMPLE_ROWS, dataArray.size()); i++) {
                samples.add(dataArray.getJSONObject(i));
            }
            JSONObject result = new JSONObject();
            result.put("dataRef", dataRef);
            result.put("rowCount", dataArray.size());
            result.put("fields", fieldDetails);
            result.put("samples", samples);
            return result.toJSONString();
        } catch (Exception e) {
            log.warn("读取图表数据 schema 失败: dataRef={}", dataRef, e);
            return ToolResultUtils.jsonTypedError("system", "读取数据 schema 失败: " + e.getMessage());
        }
    }

    /**
     * 构建 ECharts v5 option JSON。
     */
    @Tool(description = "构建 ECharts 图表：bar/line/area/pie/scatter/radar/funnel/gauge/heatmap。返回 chart-ready:N，随后必须 validateChart。")
    public String buildChart(
            @ToolParam(description = "图表类型") String chartType,
            @ToolParam(description = "业务标题") String title,
            @ToolParam(description = "真实维度字段") String dimensionField,
            @ToolParam(description = "JSON：展示名到真实指标字段的映射") String metricMapping,
            @ToolParam(description = "查询结果 outputKey") String dataRef) {
        try {
            if (StringUtils.isBlank(chartType) || StringUtils.isBlank(title)
                    || StringUtils.isBlank(dimensionField) || StringUtils.isBlank(metricMapping)) {
                return ToolResultUtils.jsonTypedError("syntax", "chartType, title, dimensionField, metricMapping 不能为空");
            }

            String truncatedError = truncatedResultError(dataRef);
            if (truncatedError != null) return truncatedError;

            // 从分析状态获取数据
            JSONArray dataArray = getDataArray(dataRef);
            if (dataArray == null) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION,
                        "数据引用不存在: " + dataRef + "。可用数据: " + analysisState.getAvailableKeys());
            }
            if (dataArray == null || dataArray.isEmpty()) {
                return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_PRECONDITION, "数据为空");
            }

            JSONObject mapping = JSON.parseObject(metricMapping);
            if (mapping == null || mapping.isEmpty()) return ToolResultUtils.jsonTypedError("syntax", "metricMapping 格式无效");

            List<String> seriesNames = new ArrayList<>(mapping.keySet());
            List<String> dataColumns = new ArrayList<>();
            for (String key : seriesNames) {
                String field = mapping.getString(key);
                if (StringUtils.isBlank(field)) {
                    return schemaError("指标映射 \"" + key + "\" 未指定真实字段名", availableFields(dataArray));
                }
                dataColumns.add(field);
            }

            List<String> fields = availableFields(dataArray);
            if (!fields.contains(dimensionField)) {
                return schemaError("维度字段 \"" + dimensionField + "\" 不存在", fields);
            }
            for (String dataColumn : dataColumns) {
                if (!fields.contains(dataColumn)) {
                    return schemaError("指标字段 \"" + dataColumn + "\" 不存在", fields);
                }
            }

            if (dataArray.size() > MAX_DIM_VALUES)
                return ToolResultUtils.jsonTypedError("syntax", "维度值数量 " + dataArray.size() + " 超过上限 " + MAX_DIM_VALUES + "，建议 Top N 或按时间聚合");

            // 提取维度值
            List<String> dimValues = new ArrayList<>();
            int nonBlankDimensionCount = 0;
            for (int i = 0; i < dataArray.size(); i++) {
                Object val = dataArray.getJSONObject(i).get(dimensionField);
                String dimensionValue = val == null ? null : val.toString();
                if (StringUtils.isNotBlank(dimensionValue)) nonBlankDimensionCount++;
                dimValues.add(dimensionValue);
            }
            if (nonBlankDimensionCount == 0) {
                return ToolResultUtils.jsonTypedError("schema", "维度字段 \"" + dimensionField + "\" 的值均为空，无法生成图表");
            }

            // 提取指标数据
            Map<String, List<Double>> metricData = new LinkedHashMap<>();
            for (int si = 0; si < seriesNames.size(); si++) {
                String colName = dataColumns.get(si);
                List<Double> values = new ArrayList<>();
                int numericValueCount = 0;
                for (int i = 0; i < dataArray.size(); i++) {
                    Object rawValue = dataArray.getJSONObject(i).get(colName);
                    Double value = toFiniteDouble(rawValue);
                    if (rawValue != null && value == null) {
                        return ToolResultUtils.jsonTypedError("schema", "指标字段 \"" + colName
                                + "\" 第 " + (i + 1) + " 行不是有效数值: " + rawValue);
                    }
                    if (value != null) numericValueCount++;
                    values.add(value);
                }
                if (numericValueCount == 0) {
                    return ToolResultUtils.jsonTypedError("schema", "指标字段 \"" + colName + "\" 没有可用数值");
                }
                metricData.put(seriesNames.get(si), values);
            }

            String optionJson = switch (normalizeChartType(chartType)) {
                case "bar", "柱状图" -> buildBar(title, dimValues, seriesNames, metricData);
                case "line", "折线图" -> buildLine(title, dimValues, seriesNames, metricData);
                case "area", "面积图" -> buildArea(title, dimValues, seriesNames, metricData);
                case "pie", "饼图" -> buildPie(title, dimValues, metricData);
                case "scatter", "散点图" -> buildScatter(title, seriesNames, metricData);
                case "radar", "雷达图" -> buildRadar(title, dimValues, seriesNames, metricData);
                case "funnel", "漏斗图" -> buildFunnel(title, dimValues, metricData);
                case "gauge", "仪表盘" -> buildGauge(title, metricData);
                case "heatmap", "热力图" -> buildHeatmap(title, dimValues, seriesNames, metricData);
                default -> ToolResultUtils.jsonTypedError("syntax", "不支持的图表类型: " + chartType);
            };

            // 系统错误自动重试一次
            if (optionJson.contains("\"error\":\"system\"")) {
                log.debug("图表重试：类型={}", chartType);
                optionJson = switch (normalizeChartType(chartType)) {
                    case "bar" -> buildBar(title, dimValues, seriesNames, metricData);
                    case "line" -> buildLine(title, dimValues, seriesNames, metricData);
                    case "area" -> buildArea(title, dimValues, seriesNames, metricData);
                    case "pie" -> buildPie(title, dimValues, metricData);
                    case "scatter" -> buildScatter(title, seriesNames, metricData);
                    case "radar" -> buildRadar(title, dimValues, seriesNames, metricData);
                    case "funnel" -> buildFunnel(title, dimValues, metricData);
                    case "gauge" -> buildGauge(title, metricData);
                    case "heatmap" -> buildHeatmap(title, dimValues, seriesNames, metricData);
                    default -> ToolResultUtils.jsonTypedError("syntax", "不支持的图表类型: " + chartType);
                };
            }

            // 存入 RunContext（供持久化），返回摘要给 LLM（避免 LLM 在文本中复述 JSON）
            if (!optionJson.contains("\"error\"")) {
                RunContext ctx = RunContextHolder.require();
                ctx.addChartOption(optionJson);
                int chartIndex = ctx.getChartOptionCount();
                log.info("chart.ready runId={} index={} type={} dataRef={}",
                        ctx.getRunId(), chartIndex, chartType, dataRef);
                return "chart-ready:" + chartIndex;
            } else {
                log.warn("图表生成失败：类型={}、数据引用={}", chartType, dataRef);
            }
            return optionJson;

        } catch (Exception e) {
            log.error("图表构建异常：数据引用={}", dataRef, e);
            analysisState.addStepResultFailed(dataRef + "_chart", "buildChart", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "图表构建失败: " + e.getMessage());
        }
    }

    /**
     * 校验 ECharts option JSON 结构完整性。
     */
    @Tool(description = "校验 buildChart 返回的 chart-ready:N；构建后必须调用。")
    public String validateChart(
            @ToolParam(description = "chart-ready:N 或 ECharts option JSON") String optionJson) {
        try {
            if (optionJson == null || optionJson.isBlank()) return ToolResultUtils.jsonTypedError("syntax", "option JSON 为空");

            String cleaned = resolveOptionJson(optionJson);
            if (cleaned == null) {
                return ToolResultUtils.jsonTypedError("syntax", "图表引用无效，请使用 buildChart 返回的 chart-ready:N");
            }

            String cacheKey = cacheManager.buildKey("validateChart", cleaned);
            String cached = cacheManager.get(cacheKey);
            if (cached != null) {
                markValidatedReference(optionJson, cached);
                return cached;
            }

            if (!cleaned.startsWith("{")) return ToolResultUtils.jsonTypedError("syntax", "不是有效的 JSON 对象");

            JSONObject option = JSON.parseObject(cleaned);
            List<String> issues = new ArrayList<>();

            if (StringUtils.isBlank(readTitle(option)))
                issues.add("缺少 title.text");

            JSONArray series = option.getJSONArray("series");
            if (series == null || series.isEmpty()) issues.add("series 为空");
            else {
                for (int i = 0; i < series.size(); i++) {
                    JSONObject s = series.getJSONObject(i);
                    if (s != null) {
                        JSONArray sData = s.getJSONArray("data");
                        if (sData == null || sData.isEmpty())
                            issues.add("series[" + i + "].data 为空");
                        if (sData != null && sData.size() > MAX_DIM_VALUES)
                            issues.add("series[" + i + "].data 超过 " + MAX_DIM_VALUES + " 个数据点");
                    } else {
                        issues.add("series[" + i + "] 无效");
                    }
                }
            }

            if (!option.containsKey("tooltip")) issues.add("缺少 tooltip");
            validateRadarOption(option, issues);

            String result;
            if (issues.isEmpty()) {
                result = "校验通过";
                markValidatedReference(optionJson, result);
            } else {
                result = ToolResultUtils.jsonTypedError("syntax", "图表结构不完整: " + String.join("; ", issues));
            }
            cacheManager.put(cacheKey, result, 600);
            return result;
        } catch (Exception e) {
            return ToolResultUtils.jsonTypedError("system", "校验异常: " + e.getMessage());
        }
    }

    private String resolveOptionJson(String rawOption) {
        String cleaned = rawOption.trim()
                .replaceAll("(?s)^```[a-z]*\\n?", "")
                .replaceAll("\\n```\\s*$", "").trim();
        if (cleaned.startsWith("{")) return cleaned;
        if (!cleaned.matches("chart-ready:[1-9]\\d*")) return null;

        int chartIndex = Integer.parseInt(cleaned.substring("chart-ready:".length()));
        RunContext context = RunContextHolder.get();
        return context == null ? null : context.getChartOption(chartIndex);
    }

    private void markValidatedReference(String reference, String validationResult) {
        if (!"校验通过".equals(validationResult) || reference == null) return;
        String cleaned = reference.trim();
        if (!cleaned.matches("chart-ready:[1-9]\\d*")) return;
        RunContext context = RunContextHolder.get();
        if (context != null) {
            context.markChartValidated(Integer.parseInt(cleaned.substring("chart-ready:".length())));
        }
    }

    private String readTitle(JSONObject option) {
        Object rawTitle = option.get("title");
        if (rawTitle instanceof JSONObject title) return title.getString("text");
        if (rawTitle instanceof JSONArray titles && !titles.isEmpty()) {
            JSONObject title = titles.getJSONObject(0);
            return title == null ? null : title.getString("text");
        }
        return null;
    }

    private JSONArray getDataArray(String dataRef) {
        String dataJson = analysisState.getDataByKey(dataRef);
        if (dataJson != null) return JSON.parseArray(dataJson);

        AnalysisState.QueryOutputRecord output = analysisState.getQueryOutput(dataRef);
        if (output == null || output.sources == null || output.sources.isEmpty()) return null;

        List<DuckDbConfig.FileRef> refs = output.sources.stream()
                .map(source -> new DuckDbConfig.FileRef(source.parquetPath, source.viewName))
                .toList();
        long startNanos = System.nanoTime();
        try {
            DuckDbQueryService.AgentQueryResult result = duckDbQueryService.executeAgentQuery(
                    RunContextHolder.require().getConversationId().toString(), refs, output.sql, output.rowLimit);
            if (result == null || result.hasError()) {
                log.warn("图表数据重算失败：引用={}", dataRef);
                return null;
            }
            return JSON.parseArray(result.dataJson());
        } finally {
            RunContext context = RunContextHolder.get();
            if (context != null) {
                context.recordQueryReplay((System.nanoTime() - startNanos) / 1_000_000);
            }
        }
    }

    private String truncatedResultError(String dataRef) {
        AnalysisState.QueryOutputRecord output = analysisState.getQueryOutput(dataRef);
        if (output == null || !output.truncated) return null;
        return ToolResultUtils.jsonTypedError("schema", "数据引用 " + dataRef + " 仅返回前"
                + output.rowLimit + "行，不能直接生成图表。请先使用聚合、筛选或 Top N 查询缩小结果。");
    }

    private List<String> availableFields(JSONArray dataArray) {
        if (dataArray == null || dataArray.isEmpty() || dataArray.getJSONObject(0) == null) return List.of();
        return new ArrayList<>(dataArray.getJSONObject(0).keySet());
    }

    private String schemaError(String message, List<String> availableFields) {
        JSONObject error = new JSONObject();
        error.put("error", "schema");
        error.put("message", message);
        error.put("availableFields", availableFields);
        return error.toJSONString();
    }

    private Double toFiniteDouble(Object rawValue) {
        if (rawValue == null) return null;
        double value;
        if (rawValue instanceof Number number) value = number.doubleValue();
        else {
            try {
                value = Double.parseDouble(rawValue.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return Double.isFinite(value) ? value : null;
    }

    // ─── 图表构建方法 ────────────────────────────────────

    private String buildBar(String title, List<String> dimValues, List<String> seriesNames, Map<String, List<Double>> metricData) {
        Bar bar = new Bar();
        bar.setTitle(title);
        bar.setLegend();
        bar.setTooltip("axis");
        bar.addXAxis(dimValues.toArray(new String[0]));
        bar.addYAxis();
        for (String name : seriesNames) bar.addSeries(name, metricData.get(name).toArray(new Number[0]));
        return render(bar);
    }

    private String buildLine(String title, List<String> dimValues, List<String> seriesNames, Map<String, List<Double>> metricData) {
        Line line = new Line();
        line.setTitle(title);
        line.setLegend();
        line.setTooltip("axis");
        line.addXAxis(dimValues.toArray(new String[0]));
        line.addYAxis();
        for (String name : seriesNames) line.addSeries(name, metricData.get(name).toArray(new Number[0]));
        return render(line);
    }

    private String buildArea(String title, List<String> dimValues, List<String> seriesNames, Map<String, List<Double>> metricData) {
        Line line = new Line();
        line.setTitle(title);
        line.setLegend();
        line.setTooltip("axis");
        line.addXAxis(dimValues.toArray(new String[0]));
        line.addYAxis();
        for (String name : seriesNames) {
            List<Double> values = metricData.get(name);
            line.addSeries(new LineSeries().setName(name).setData(values.toArray(new Number[0])).setAreaStyle(new LineAreaStyle()));
        }
        return render(line);
    }

    private String buildPie(String title, List<String> dimValues, Map<String, List<Double>> metricData) {
        Pie pie = new Pie();
        pie.setTitle(title);
        pie.setLegend();
        pie.setTooltip("item");
        String firstSeries = metricData.keySet().iterator().next();
        List<Double> values = metricData.get(firstSeries);
        List<Map<String, Object>> pieData = new ArrayList<>();
        for (int i = 0; i < dimValues.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", dimValues.get(i));
            item.put("value", values.get(i));
            pieData.add(item);
        }
        pie.addSeries(new PieSeries().setName(firstSeries).setData(pieData));
        return render(pie);
    }

    private String buildScatter(String title, List<String> seriesNames, Map<String, List<Double>> metricData) {
        JSONArray data = new JSONArray();
        List<Double> xVals = metricData.get(seriesNames.get(0));
        List<Double> yVals = metricData.get(seriesNames.size() > 1 ? seriesNames.get(1) : seriesNames.get(0));
        int len = Math.min(xVals.size(), yVals.size());
        for (int i = 0; i < len; i++) data.add(JSONArray.of(xVals.get(i), yVals.get(i)));

        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("trigger", "axis"); }});
        option.put("xAxis", JSONArray.of(new JSONObject() {{ put("type", "value"); put("name", seriesNames.get(0)); }}));
        option.put("yAxis", JSONArray.of(new JSONObject() {{ put("type", "value"); put("name", seriesNames.size() > 1 ? seriesNames.get(1) : seriesNames.get(0)); }}));
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", "数据点"); put("type", "scatter"); put("data", data); put("symbolSize", 10); }}));
        return option.toJSONString();
    }

    private String buildRadar(String title, List<String> dimValues, List<String> seriesNames,
                              Map<String, List<Double>> metricData) {
        if (dimValues.size() < 3 && seriesNames.size() >= 3) {
            return buildTransposedRadar(title, dimValues, seriesNames, metricData);
        }
        if (dimValues.size() < 3) {
            return ToolResultUtils.jsonTypedError("schema", "雷达图至少需要 3 个维度；当前只有 "
                    + dimValues.size() + " 个维度和 " + seriesNames.size() + " 个指标，建议改用柱状图");
        }
        JSONArray indicators = new JSONArray();
        for (int index = 0; index < dimValues.size(); index++) {
            String dimension = dimValues.get(index);
            if (StringUtils.isBlank(dimension)) {
                return ToolResultUtils.jsonTypedError("schema", "雷达图维度值不能为空");
            }
            double max = 0D;
            for (List<Double> values : metricData.values()) {
                if (index >= values.size() || values.get(index) == null) {
                    return ToolResultUtils.jsonTypedError("schema", "雷达图指标数据不完整");
                }
                max = Math.max(max, Math.abs(values.get(index)));
            }
            JSONObject indicator = new JSONObject();
            indicator.put("name", dimension);
            indicator.put("max", Math.max(1D, Math.ceil(max * 1.2D)));
            indicators.add(indicator);
        }

        JSONArray radarData = new JSONArray();
        for (String name : seriesNames) {
            List<Double> values = metricData.get(name);
            if (values == null || values.size() != dimValues.size()) {
                return ToolResultUtils.jsonTypedError("schema", "雷达图指标与维度数量不一致");
            }
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("value", new JSONArray(values));
            radarData.add(item);
        }
        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("trigger", "item"); }});
        option.put("radar", new JSONObject() {{ put("indicator", indicators); }});
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", title); put("type", "radar");
            put("data", radarData); }}));
        return option.toJSONString();
    }

    /**
     * 少量对象、多指标场景的雷达图：指标作为雷达维度，每行对象作为一个系列。
     * 例如两个模型同时比较 R²、RMSE、MAE 时，两个模型均可在三项指标上形成完整雷达面。
     */
    private String buildTransposedRadar(String title, List<String> dimValues, List<String> seriesNames,
                                        Map<String, List<Double>> metricData) {
        JSONArray indicators = new JSONArray();
        for (String seriesName : seriesNames) {
            List<Double> values = metricData.get(seriesName);
            if (values == null || values.size() != dimValues.size() || values.stream().anyMatch(Objects::isNull)) {
                return ToolResultUtils.jsonTypedError("schema", "雷达图指标数据不完整");
            }
            double max = values.stream().mapToDouble(Math::abs).max().orElse(0D);
            JSONObject indicator = new JSONObject();
            indicator.put("name", seriesName);
            indicator.put("max", Math.max(1D, Math.ceil(max * 1.2D)));
            indicators.add(indicator);
        }

        JSONArray radarData = new JSONArray();
        for (int rowIndex = 0; rowIndex < dimValues.size(); rowIndex++) {
            String objectName = dimValues.get(rowIndex);
            if (StringUtils.isBlank(objectName)) {
                return ToolResultUtils.jsonTypedError("schema", "雷达图对象名称不能为空");
            }
            JSONArray values = new JSONArray();
            for (String seriesName : seriesNames) {
                values.add(metricData.get(seriesName).get(rowIndex));
            }
            JSONObject item = new JSONObject();
            item.put("name", objectName);
            item.put("value", values);
            radarData.add(item);
        }

        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("trigger", "item"); }});
        option.put("radar", new JSONObject() {{ put("indicator", indicators); }});
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", title); put("type", "radar");
            put("data", radarData); }}));
        return option.toJSONString();
    }

    private void validateRadarOption(JSONObject option, List<String> issues) {
        JSONObject radar = option.getJSONObject("radar");
        if (radar == null) return;
        JSONArray indicators = radar.getJSONArray("indicator");
        if (indicators == null || indicators.size() < 3) {
            issues.add("雷达图至少需要 3 个有效维度");
            return;
        }
        JSONArray series = option.getJSONArray("series");
        if (series == null) return;
        for (int seriesIndex = 0; seriesIndex < series.size(); seriesIndex++) {
            JSONObject seriesItem = series.getJSONObject(seriesIndex);
            JSONArray data = seriesItem == null ? null : seriesItem.getJSONArray("data");
            if (data == null) continue;
            for (int dataIndex = 0; dataIndex < data.size(); dataIndex++) {
                JSONObject item = data.getJSONObject(dataIndex);
                JSONArray values = item == null ? null : item.getJSONArray("value");
                if (values == null || values.size() != indicators.size()) {
                    issues.add("雷达图数据向量与维度数量不一致");
                    continue;
                }
                for (Object value : values) {
                    if (toFiniteDouble(value) == null) {
                        issues.add("雷达图包含无效数值");
                        break;
                    }
                }
            }
        }
    }

    private String buildFunnel(String title, List<String> dimValues, Map<String, List<Double>> metricData) {
        String name = metricData.keySet().iterator().next();
        List<Double> values = metricData.get(name);
        JSONArray data = new JSONArray();
        for (int i = 0; i < dimValues.size(); i++) {
            JSONObject item = new JSONObject();
            item.put("name", dimValues.get(i));
            item.put("value", values.get(i));
            data.add(item);
        }
        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("trigger", "item"); }});
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", title); put("type", "funnel");
            put("left", "10%"); put("width", "80%"); put("data", data); }}));
        return option.toJSONString();
    }

    private String buildGauge(String title, Map<String, List<Double>> metricData) {
        String name = metricData.keySet().iterator().next();
        double total = metricData.get(name).stream().mapToDouble(Double::doubleValue).sum();
        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("formatter", "{a} <br/>{b} : {c}"); }});
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", name); put("type", "gauge");
            put("min", 0); put("max", Math.max(total * 1.5, 100));
            put("detail", new JSONObject() {{ put("formatter", "{value}"); }});
            put("data", JSONArray.of(new JSONObject() {{ put("value", total); put("name", name); }})); }}));
        return option.toJSONString();
    }

    private String buildHeatmap(String title, List<String> dimValues, List<String> seriesNames, Map<String, List<Double>> metricData) {
        JSONArray xData = new JSONArray(dimValues.size());
        dimValues.forEach(xData::add);
        JSONArray yData = new JSONArray(seriesNames.size());
        seriesNames.forEach(yData::add);
        JSONArray heatData = new JSONArray();
        for (int yi = 0; yi < seriesNames.size(); yi++) {
            List<Double> values = metricData.get(seriesNames.get(yi));
            for (int xi = 0; xi < Math.min(values.size(), dimValues.size()); xi++)
                heatData.add(JSONArray.of(xi, yi, values.get(xi)));
        }
        double maxVal = metricData.values().stream().flatMap(List::stream).mapToDouble(Double::doubleValue).max().orElse(100);
        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("trigger", "item"); }});
        option.put("xAxis", JSONArray.of(new JSONObject() {{ put("type", "category"); put("data", xData); put("splitArea", new JSONObject() {{ put("show", true); }}); }}));
        option.put("yAxis", JSONArray.of(new JSONObject() {{ put("type", "category"); put("data", yData); put("splitArea", new JSONObject() {{ put("show", true); }}); }}));
        option.put("visualMap", new JSONObject() {{ put("min", 0); put("max", maxVal); put("calculable", true);
            put("inRange", new JSONObject() {{ put("color", JSONArray.of("#313695", "#4575b4", "#74add1", "#abd9e9", "#fee090", "#fdae61", "#f46d43", "#d73027")); }}); }});
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", title); put("type", "heatmap");
            put("data", heatData); put("label", new JSONObject() {{ put("show", true); }}); }}));
        return option.toJSONString();
    }

    private static String render(Chart<?, ?> chart) { return new Engine().renderJsonOption(chart); }

    private static String normalizeChartType(String type) {
        if (type == null) return "";
        String t = type.trim().toLowerCase();
        if (t.contains("折线") || t.equals("line")) return "line";
        if (t.contains("柱") || t.equals("bar")) return "bar";
        if (t.contains("饼") || t.equals("pie")) return "pie";
        if (t.contains("散点") || t.equals("scatter")) return "scatter";
        if (t.contains("雷达") || t.equals("radar")) return "radar";
        if (t.contains("漏斗") || t.equals("funnel")) return "funnel";
        if (t.contains("仪表") || t.equals("gauge")) return "gauge";
        if (t.contains("热力") || t.equals("heatmap")) return "heatmap";
        if (t.contains("面积") || t.equals("area")) return "area";
        return type;
    }

}
