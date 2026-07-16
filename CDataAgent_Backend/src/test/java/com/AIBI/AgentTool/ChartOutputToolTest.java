package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartOutputToolTest {

    private ChartOutputTool chartOutputTool;
    private AnalysisState analysisState;

    @BeforeEach
    void setUp() throws Exception {
        chartOutputTool = new ChartOutputTool();
        analysisState = new AnalysisState();
        analysisState.setCurrentThreadId("test-conversation");
        var field = ChartOutputTool.class.getDeclaredField("analysisState");
        field.setAccessible(true);
        field.set(chartOutputTool, analysisState);
        RunContextHolder.set(new RunContext("run-test", 1L));
    }

    @AfterEach
    void tearDown() {
        RunContextHolder.clear();
    }

    @Test
    void buildsChartUsingExactSchemaFields() {
        analysisState.addData("monthly_prices", "[{\"month\":\"2025-01\",\"close\":10.5},{\"month\":\"2025-02\",\"close\":12.0}]");

        String result = chartOutputTool.buildChart("line", "ETF 收盘价", "month",
                "{\"收盘价\":\"close\"}", "monthly_prices");

        assertEquals("chart-ready:1", result);
        JSONObject option = JSON.parseObject(RunContextHolder.get().getChartOption(1));
        assertEquals("2025-01", option.getJSONArray("xAxis").getJSONObject(0).getJSONArray("data").getString(0));
        assertEquals(10.5D, option.getJSONArray("series").getJSONObject(0).getJSONArray("data").getDouble(0));
    }

    @Test
    void rejectsUnknownFieldsInsteadOfGeneratingBlankAndZeroChart() {
        analysisState.addData("monthly_prices", "[{\"month\":\"2025-01\",\"close\":10.5}]");

        String result = chartOutputTool.buildChart("line", "ETF 收盘价", "日期",
                "{\"收盘价\":\"收盘价\"}", "monthly_prices");

        JSONObject error = JSON.parseObject(result);
        assertEquals("schema", error.getString("error"));
        assertTrue(error.getJSONArray("availableFields").contains("month"));
        assertTrue(RunContextHolder.get().peekChartOptions() == null);
    }

    @Test
    void describesSchemaWithExactFieldNamesAndSamples() {
        analysisState.addData("monthly_prices", "[{\"month\":\"2025-01\",\"close\":10.5}]");

        JSONObject description = JSON.parseObject(chartOutputTool.describeData("monthly_prices"));
        JSONArray fields = description.getJSONArray("fields");

        assertEquals("month", fields.getJSONObject(0).getString("name"));
        assertEquals("2025-01", description.getJSONArray("samples").getJSONObject(0).getString("month"));
    }

    @Test
    void buildsTransposedRadarForFewObjectsAndMultipleMetrics() {
        analysisState.addData("model_metrics", """
                [{"model":"模型A","r2":0.91,"rmse":12.0,"mae":8.0},
                 {"model":"模型B","r2":0.87,"rmse":15.0,"mae":10.0}]
                """);

        String result = chartOutputTool.buildChart("radar", "模型性能对比", "model",
                "{\"R²\":\"r2\",\"RMSE\":\"rmse\",\"MAE\":\"mae\"}", "model_metrics");

        assertEquals("chart-ready:1", result);
        JSONObject option = JSON.parseObject(RunContextHolder.get().getChartOption(1));
        JSONArray indicators = option.getJSONObject("radar").getJSONArray("indicator");
        JSONArray data = option.getJSONArray("series").getJSONObject(0).getJSONArray("data");
        assertEquals(3, indicators.size());
        assertEquals("R²", indicators.getJSONObject(0).getString("name"));
        assertEquals("模型A", data.getJSONObject(0).getString("name"));
        assertEquals(3, data.getJSONObject(0).getJSONArray("value").size());
    }

    @Test
    void rejectsTruncatedQueryResultBeforeBuildingChart() {
        AnalysisState.QueryOutputRecord output = new AnalysisState.QueryOutputRecord();
        output.outputKey = "raw_detail";
        output.rowLimit = 1000;
        output.truncated = true;
        analysisState.addQueryOutput(output);

        JSONObject error = JSON.parseObject(chartOutputTool.buildChart("bar", "明细", "month",
                "{\"销售额\":\"sales\"}", "raw_detail"));

        assertEquals("schema", error.getString("error"));
        assertTrue(error.getString("message").contains("Top N"));
    }
}
