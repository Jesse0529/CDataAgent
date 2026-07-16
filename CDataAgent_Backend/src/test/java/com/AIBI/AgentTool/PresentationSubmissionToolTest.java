package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationSubmissionToolTest {

    private PresentationSubmissionTool tool;
    private AnalysisState analysisState;

    @BeforeEach
    void setUp() throws Exception {
        tool = new PresentationSubmissionTool();
        analysisState = new AnalysisState();
        analysisState.setCurrentThreadId("test-conversation");
        var field = PresentationSubmissionTool.class.getDeclaredField("analysisState");
        field.setAccessible(true);
        field.set(tool, analysisState);
        RunContext context = new RunContext("run-test", 1L);
        context.setIntent("analysis", List.of(), List.of(), "clear", "测试", List.of("table"));
        RunContextHolder.set(context);
    }

    @AfterEach
    void tearDown() {
        RunContextHolder.clear();
    }

    @Test
    void acceptsExistingInlineResult() {
        analysisState.addData("sales_by_region", "[{\"region\":\"华东\",\"sales\":100}]");

        String result = tool.submitPresentation("华东销售额最高", List.of(),
                List.of("sales_by_region"), List.of("sales_by_region"));

        assertTrue(result.startsWith("展示计划已提交"));
        assertEquals(List.of("sales_by_region"), RunContextHolder.get()
                .getPresentationPlan().getTableOutputKeys());
    }

    @Test
    void rejectsUnknownOutputKey() {
        String result = tool.submitPresentation("结论", List.of(), List.of("missing"), List.of());

        assertPrecondition(result);
        assertNull(RunContextHolder.get().getPresentationPlan());
    }

    @Test
    void rejectsTruncatedOutputKey() {
        AnalysisState.QueryOutputRecord output = new AnalysisState.QueryOutputRecord();
        output.outputKey = "raw_detail";
        output.rowLimit = 1000;
        output.truncated = true;
        analysisState.addQueryOutput(output);

        String result = tool.submitPresentation("结论", List.of(), List.of(), List.of("raw_detail"));

        assertPrecondition(result);
        assertTrue(JSON.parseObject(result).getString("message").contains("仅返回前1000行"));
    }

    @Test
    void rejectsLargeResultAsTable() {
        AnalysisState.QueryOutputRecord output = new AnalysisState.QueryOutputRecord();
        output.outputKey = "large_result";
        output.storageMode = "requery";
        analysisState.addQueryOutput(output);

        String result = tool.submitPresentation("结论", List.of(), List.of("large_result"), List.of());

        assertPrecondition(result);
        assertTrue(JSON.parseObject(result).getString("message").contains("较大结果"));
    }

    private static void assertPrecondition(String result) {
        JSONObject error = JSON.parseObject(result);
        assertEquals("precondition", error.getString("error"));
    }
}
