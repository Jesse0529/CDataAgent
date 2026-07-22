package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.DataFile;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void loadDataWithoutIntentChecksAttachmentPrecondition() throws Exception {
        DataLoadingTool tool = new DataLoadingTool();
        injectAnalysisState(tool);

        String result = tool.loadData();
        assertPrecondition(result);
        assertTrue(JSON.parseObject(result).getString("message").contains("附加到消息"));
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

    @Test
    void explicitFileScopeRejectsPartialLoad() throws Exception {
        DataLoadingTool tool = new DataLoadingTool();
        injectAnalysisState(tool);
        DataFileMapper mapper = mock(DataFileMapper.class);
        DataFile available = new DataFile();
        available.setId(1L);
        available.setConversationId(1L);
        available.setStatus("READY");
        when(mapper.selectList(any())).thenReturn(List.of(available));
        setField(tool, "dataFileMapper", mapper);
        RunContextHolder.get().setFileScope(true, List.of(1L, 2L));

        String result = tool.loadData();

        assertPrecondition(result);
        assertTrue(analysisState.getLoadedFiles().isEmpty());
        assertTrue(!RunContextHolder.get().isFileScopeAvailable());
    }

    private void injectAnalysisState(Object tool) throws Exception {
        setField(tool, "analysisState", analysisState);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertPrecondition(String result) {
        assertEquals("precondition", JSON.parseObject(result).getString("error"));
    }
}
