package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
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

    private static final int MAX_DIM_VALUES = 80;
    private static final Set<String> GENERIC_NAMES = Set.of("series1", "series2", "series3", "数据", "数值", "值");

    /**
     * 构建 ECharts v5 option JSON。
     */
    @Tool(description = "使用 echarts-java 确定性构建 ECharts v5 option JSON。" +
            "支持 bar/line/area/pie/scatter/radar/funnel/gauge/heatmap。" +
            "✅ 生成图表配置 ❌ 不用于数据分析。dataRef 为 runDuckdb 或 runPython 的 outputKey")
    public String buildChart(
            @ToolParam(description = "图表类型：bar/line/area/pie/scatter/radar/funnel/gauge/heatmap") String chartType,
            @ToolParam(description = "图表标题，如 '2024年各区域销售额对比'") String title,
            @ToolParam(description = "维度字段名（X 轴），与数据中的键对应，如 region") String dimensionField,
            @ToolParam(description = "指标映射 JSON：{\"系列展示名\":\"数据列名\"}，如 {\"销售额\":\"sales\"}") String metricMapping,
            @ToolParam(description = "数据 outputKey（来自 runDuckdb 或 runPython 的结果引用名）") String dataRef) {
        try {
            if (StringUtils.isBlank(chartType) || StringUtils.isBlank(title)
                    || StringUtils.isBlank(dimensionField) || StringUtils.isBlank(metricMapping)) {
                return ToolResultUtils.jsonTypedError("syntax", "chartType, title, dimensionField, metricMapping 不能为空");
            }

            // 从分析状态获取数据
            String dataJson = analysisState.getDataByKey(dataRef);
            if (dataJson == null) {
                return ToolResultUtils.jsonTypedError("syntax", "数据引用不存在: " + dataRef + "。可用数据: " + analysisState.getAvailableKeys());
            }

            JSONArray dataArray = JSON.parseArray(dataJson);
            if (dataArray == null || dataArray.isEmpty()) return ToolResultUtils.jsonTypedError("syntax", "数据为空");

            JSONObject mapping = JSON.parseObject(metricMapping);
            if (mapping == null || mapping.isEmpty()) return ToolResultUtils.jsonTypedError("syntax", "metricMapping 格式无效");

            List<String> seriesNames = new ArrayList<>(mapping.keySet());
            List<String> dataColumns = new ArrayList<>();
            for (String key : seriesNames) dataColumns.add(mapping.getString(key));

            if (dataArray.size() > MAX_DIM_VALUES)
                return ToolResultUtils.jsonTypedError("syntax", "维度值数量 " + dataArray.size() + " 超过上限 " + MAX_DIM_VALUES + "，建议 Top N 或按时间聚合");

            // 提取维度值
            List<String> dimValues = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                Object val = dataArray.getJSONObject(i).get(dimensionField);
                dimValues.add(val != null ? val.toString() : "");
            }

            // 提取指标数据
            Map<String, List<Double>> metricData = new LinkedHashMap<>();
            for (int si = 0; si < seriesNames.size(); si++) {
                String colName = dataColumns.get(si);
                List<Double> values = new ArrayList<>();
                for (int i = 0; i < dataArray.size(); i++) {
                    Number val = dataArray.getJSONObject(i).getDouble(colName);
                    values.add(val != null ? val.doubleValue() : 0.0);
                }
                metricData.put(seriesNames.get(si), values);
            }

            String optionJson = switch (normalizeChartType(chartType)) {
                case "bar", "柱状图" -> buildBar(title, dimValues, seriesNames, metricData);
                case "line", "折线图" -> buildLine(title, dimValues, seriesNames, metricData);
                case "area", "面积图" -> buildArea(title, dimValues, seriesNames, metricData);
                case "pie", "饼图" -> buildPie(title, dimValues, metricData);
                case "scatter", "散点图" -> buildScatter(title, seriesNames, metricData);
                case "radar", "雷达图" -> buildRadar(title, seriesNames, metricData);
                case "funnel", "漏斗图" -> buildFunnel(title, dimValues, metricData);
                case "gauge", "仪表盘" -> buildGauge(title, metricData);
                case "heatmap", "热力图" -> buildHeatmap(title, dimValues, seriesNames, metricData);
                default -> ToolResultUtils.jsonTypedError("syntax", "不支持的图表类型: " + chartType);
            };

            // 系统错误自动重试一次
            if (optionJson.contains("\"error\":\"system\"")) {
                log.warn("buildChart: 系统错误，重试一次: type={}, title={}", chartType, title);
                optionJson = switch (normalizeChartType(chartType)) {
                    case "bar" -> buildBar(title, dimValues, seriesNames, metricData);
                    case "line" -> buildLine(title, dimValues, seriesNames, metricData);
                    case "area" -> buildArea(title, dimValues, seriesNames, metricData);
                    case "pie" -> buildPie(title, dimValues, metricData);
                    case "scatter" -> buildScatter(title, seriesNames, metricData);
                    case "radar" -> buildRadar(title, seriesNames, metricData);
                    case "funnel" -> buildFunnel(title, dimValues, metricData);
                    case "gauge" -> buildGauge(title, metricData);
                    case "heatmap" -> buildHeatmap(title, dimValues, seriesNames, metricData);
                    default -> ToolResultUtils.jsonTypedError("syntax", "不支持的图表类型: " + chartType);
                };
            }

            // 存入 AnalysisState（供持久化），返回摘要给 LLM（避免 LLM 在文本中复述 JSON）
            if (!optionJson.contains("\"error\"")) {
                analysisState.addChartOption(optionJson);
            }
            log.info("buildChart: cid={}, type={}, title={}, dataRef={}",
                    analysisState.getCurrentThreadId(), chartType, title, dataRef);
            return optionJson.contains("\"error\"")
                    ? optionJson
                    : "图表已生成: " + chartType + ", 标题=" + title;

        } catch (Exception e) {
            log.error("buildChart 失败", e);
            analysisState.addStepResultFailed(dataRef + "_chart", "buildChart", e.getMessage());
            return ToolResultUtils.jsonTypedError("system", "图表构建失败: " + e.getMessage());
        }
    }

    /**
     * 校验 ECharts option JSON 结构完整性。
     */
    @Tool(description = "校验 ECharts option JSON 的结构完整性。✅ 构建图表后必须调用此工具兜底校验")
    public String validateChart(
            @ToolParam(description = "待校验的 ECharts option JSON 字符串") String optionJson) {
        String cacheKey = cacheManager.buildKey("validateChart", optionJson);
        String cached = cacheManager.get(cacheKey);
        if (cached != null) return cached;

        try {
            if (optionJson == null || optionJson.isBlank()) return ToolResultUtils.jsonValidResult(false, "option JSON 为空");

            String cleaned = optionJson.trim()
                    .replaceAll("(?s)^```[a-z]*\\n?", "")
                    .replaceAll("\\n```\\s*$", "").trim();

            if (!cleaned.startsWith("{")) return ToolResultUtils.jsonValidResult(false, "不是有效的 JSON 对象");

            JSONObject option = JSON.parseObject(cleaned);
            List<String> issues = new ArrayList<>();

            JSONObject titleObj = option.getJSONObject("title");
            if (titleObj == null || StringUtils.isBlank(titleObj.getString("text")))
                issues.add("缺少 title.text");

            JSONArray series = option.getJSONArray("series");
            if (series == null || series.isEmpty()) issues.add("series 为空");
            else {
                for (int i = 0; i < series.size(); i++) {
                    JSONObject s = series.getJSONObject(i);
                    if (s != null) {
                        String name = s.getString("name");
                        if (name == null || GENERIC_NAMES.contains(name.trim().toLowerCase()))
                            issues.add("series[" + i + "].name 缺少业务含义");
                        JSONArray sData = s.getJSONArray("data");
                        if (sData != null && sData.size() > MAX_DIM_VALUES)
                            issues.add("series[" + i + "].data 超过 " + MAX_DIM_VALUES + " 个数据点");
                    }
                }
            }

            if (!option.containsKey("tooltip")) issues.add("缺少 tooltip");
            if (!option.containsKey("legend")) issues.add("缺少 legend");

            String result = issues.isEmpty()
                    ? ToolResultUtils.jsonValidResult(true, "校验通过")
                    : ToolResultUtils.jsonValidResult(false, String.join("; ", issues));
            cacheManager.put(cacheKey, result, 600);
            return result;
        } catch (Exception e) {
            return ToolResultUtils.jsonValidResult(false, "校验异常: " + e.getMessage());
        }
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

    private String buildRadar(String title, List<String> seriesNames, Map<String, List<Double>> metricData) {
        JSONArray indicators = new JSONArray();
        JSONArray values = new JSONArray();
        for (String name : seriesNames) {
            List<Double> vals = metricData.get(name);
            double max = vals.stream().mapToDouble(Double::doubleValue).max().orElse(100);
            indicators.add(new JSONObject() {{ put("name", name); put("max", Math.ceil(max * 1.2)); }});
            values.add(vals.isEmpty() ? 0 : vals.get(0));
        }
        JSONObject option = new JSONObject();
        option.put("title", new JSONObject() {{ put("text", title); put("left", "center"); }});
        option.put("tooltip", new JSONObject() {{ put("trigger", "item"); }});
        option.put("radar", new JSONObject() {{ put("indicator", indicators); }});
        option.put("series", JSONArray.of(new JSONObject() {{ put("name", title); put("type", "radar");
            put("data", JSONArray.of(new JSONObject() {{ put("value", values); put("name", title); }})); }}));
        return option.toJSONString();
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
