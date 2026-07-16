package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPreconditionTest {

    private AnalysisState analysisState;

    @BeforeEach
    void setUp() {
        analysisState = new AnalysisState();
        analysisState.setCurrentThreadId("test-conversation");
        RunContextHolder.set(new RunContext("run-test", 1L));
    }

    @AfterEach
    void tearDown() {
        RunContextHolder.clear();
    }

    @Test
    void loadDataRequiresDeclaredIntent() throws Exception {
        DataLoadingTool tool = new DataLoadingTool();
        injectAnalysisState(tool);

        assertPrecondition(tool.loadData());
    }

    @Test
    void queryRequiresAnalysisIntent() throws Exception {
        DuckDbQueryTool tool = new DuckDbQueryTool();
        injectAnalysisState(tool);
        RunContextHolder.get().setIntent("vague", null, null, "vague", null, null);

        assertPrecondition(tool.runDuckdb("SELECT 1", "test_result"));
    }

    @Test
    void statisticsRejectsUnknownColumnBeforeQuery() throws Exception {
        DuckDbQueryTool tool = new DuckDbQueryTool();
        injectAnalysisState(tool);
        RunContextHolder.get().setIntent("analysis", null, null, "clear", null, null);

        AnalysisState.LoadedFileRecord file = new AnalysisState.LoadedFileRecord();
        file.viewName = "housing";
        file.parquetPath = "unused.parquet";
        file.columns = List.of(new AnalysisState.ColumnRecord("price", "DOUBLE", null));
        analysisState.setLoadedFiles(List.of(file));

        assertPrecondition(tool.queryStatistics("unit_price_num,price"));
    }

    private void injectAnalysisState(Object tool) throws Exception {
        var field = tool.getClass().getDeclaredField("analysisState");
        field.setAccessible(true);
        field.set(tool, analysisState);
    }

    private static void assertPrecondition(String result) {
        assertEquals("precondition", JSON.parseObject(result).getString("error"));
    }
}
