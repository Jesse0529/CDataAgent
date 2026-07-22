package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.service.DuckDbQueryService;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuckDbQueryToolTest {

    @AfterEach
    void tearDown() {
        RunContextHolder.clear();
    }

    @Test
    void rejectsOutputKeyBoundToDifferentQuery() throws Exception {
        AnalysisState state = new AnalysisState();
        state.setCurrentThreadId("test-conversation");
        AnalysisState.LoadedFileRecord file = new AnalysisState.LoadedFileRecord();
        file.fileId = "1";
        file.viewName = "test_view";
        file.parquetPath = "test-output-key.parquet";
        state.setLoadedFiles(List.of(file));

        DuckDbQueryService service = mock(DuckDbQueryService.class);
        when(service.executeAgentQuery(any(), anyList(), any(), anyInt()))
                .thenReturn(new DuckDbQueryService.AgentQueryResult("[{\"value\":1}]", 1, false, 1000));

        DuckDbQueryTool tool = new DuckDbQueryTool();
        setField(tool, "analysisState", state);
        setField(tool, "duckDbQueryService", service);
        setField(tool, "agentMaxResultRows", 1000);
        RunContext context = new RunContext("run-test", 1L);
        context.setIntent("analysis", List.of(), List.of(), "clear", "test", List.of());
        RunContextHolder.set(context);

        tool.runDuckdb("SELECT 1 AS value", "same_key");
        String conflict = tool.runDuckdb("SELECT 2 AS value", "same_key");

        assertEquals("output_key_conflict", JSON.parseObject(conflict).getString("error"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
