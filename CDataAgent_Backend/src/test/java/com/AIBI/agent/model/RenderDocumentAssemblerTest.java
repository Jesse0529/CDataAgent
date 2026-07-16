package com.AIBI.agent.model;

import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RenderDocumentAssembler 单元测试。
 */
@DisplayName("RenderDocumentAssembler 测试")
class RenderDocumentAssemblerTest {

    private RenderDocumentAssembler assembler;
    private AnalysisState analysisState;

    @BeforeEach
    void setUp() {
        assembler = new RenderDocumentAssembler();
        analysisState = new AnalysisState();
        // 注入 AnalysisState（绕过 @Autowired）
        try {
            var field = RenderDocumentAssembler.class.getDeclaredField("analysisState");
            field.setAccessible(true);
            field.set(assembler, analysisState);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        RunContextHolder.clear();
    }

    // ── 空/无效计划 ──

    @Test
    @DisplayName("null 计划 → 降级文档")
    void nullPlanReturnsDegraded() {
        RenderDocument doc = assembler.assemble(null, "run-001");
        assertNotNull(doc);
        assertEquals(1, doc.version());
        assertEquals("run-001", doc.runId());
        assertTrue(doc.degraded());
        assertFalse(doc.blocks().isEmpty());
        assertEquals("notice", doc.blocks().get(0).getType());
    }

    @Test
    @DisplayName("空计划 → 降级文档")
    void emptyPlanReturnsDegraded() {
        PresentationPlan plan = new PresentationPlan();
        RenderDocument doc = assembler.assemble(plan, "run-002");
        assertNotNull(doc);
        assertTrue(doc.degraded());
    }

    // ── 有效摘要 ──

    @Test
    @DisplayName("有效 summary → 包含 SummaryBlock")
    void validSummaryProducesBlock() {
        PresentationPlan plan = new PresentationPlan();
        plan.setSummary("2024年销售额增长20%，各区域表现均超出预期。");

        RenderDocument doc = assembler.assemble(plan, "run-003");
        assertNotNull(doc);
        assertFalse(doc.degraded());
        assertEquals(1, doc.blocks().size());
        assertEquals("summary", doc.blocks().get(0).getType());
        SummaryBlock block = (SummaryBlock) doc.blocks().get(0);
        assertTrue(block.text().contains("2024年"));
    }

    // ── 有效列表 ──

    @Test
    @DisplayName("有效 bullets → 包含 BulletListBlock")
    void validBulletsProducesBlock() {
        PresentationPlan plan = new PresentationPlan();
        plan.setBulletItems(List.of("华北区增长最快", "华南区利润最高", "西部地区待开发"));

        RenderDocument doc = assembler.assemble(plan, "run-004");
        assertNotNull(doc);
        assertEquals(1, doc.blocks().size());
        assertEquals("bullets", doc.blocks().get(0).getType());
        BulletListBlock block = (BulletListBlock) doc.blocks().get(0);
        assertEquals(3, block.items().size());
    }

    // ── 表格引用 ──

    @Test
    @DisplayName("有效表格 outputKey → 包含 TableBlock")
    void validTableReferenceProducesBlock() {
        // 设置 AnalysisState 数据
        analysisState.setCurrentThreadId("test-thread");
        String testData = "[{\"name\":\"北京\",\"sales\":1000},{\"name\":\"上海\",\"sales\":800}]";
        analysisState.addData("query_result_1", testData);

        PresentationPlan plan = new PresentationPlan();
        plan.setTableOutputKeys(List.of("query_result_1"));

        RenderDocument doc = assembler.assemble(plan, "run-005");
        assertNotNull(doc);
        // 至少有 table block
        boolean hasTable = doc.blocks().stream().anyMatch(b -> "table".equals(b.getType()));
        assertTrue(hasTable, "应包含表格区块");
    }

    @Test
    @DisplayName("无效表格 outputKey → NoticeBlock + degraded")
    void invalidTableReferenceProducesNotice() {
        analysisState.setCurrentThreadId("test-thread");

        PresentationPlan plan = new PresentationPlan();
        plan.setTableOutputKeys(List.of("nonexistent_key"));

        RenderDocument doc = assembler.assemble(plan, "run-006");
        assertNotNull(doc);
        assertTrue(doc.degraded());
        // 应该有 notice 区块
        boolean hasNotice = doc.blocks().stream().anyMatch(b -> "notice".equals(b.getType()));
        assertTrue(hasNotice, "应包含 notice 区块");
    }

    // ── 图表引用 ──

    @Test
    @DisplayName("图表 outputKey 无可用图表 → info notice")
    void chartWithoutDataProducesInfo() {
        PresentationPlan plan = new PresentationPlan();
        plan.setChartOutputKeys(List.of("chart_1"));

        RenderDocument doc = assembler.assemble(plan, "run-007");
        assertNotNull(doc);
        boolean hasChartOrNotice = doc.blocks().stream()
                .anyMatch(b -> "chart".equals(b.getType()) || "notice".equals(b.getType()));
        assertTrue(hasChartOrNotice);
    }

    @Test
    @DisplayName("有可用图表时 → ChartBlock")
    void chartWithDataProducesChartBlock() {
        RunContext ctx = new RunContext("run-008", 1L);
        ctx.addChartOption("{\"title\":{\"text\":\"test\"}}");
        ctx.markChartValidated(1);
        RunContextHolder.set(ctx);

        PresentationPlan plan = new PresentationPlan();
        plan.setChartOutputKeys(List.of("chart_1"));

        RenderDocument doc = assembler.assemble(plan, "run-008");
        assertNotNull(doc);
        boolean hasChart = doc.blocks().stream().anyMatch(b -> "chart".equals(b.getType()));
        assertTrue(hasChart, "应包含图表区块");
    }

    // ── 版本检查 ──

    @Test
    @DisplayName("生成的文档版本号固定为 1")
    void documentVersionIsOne() {
        PresentationPlan plan = new PresentationPlan();
        plan.setSummary("测试");
        RenderDocument doc = assembler.assemble(plan, "run-009");
        assertEquals(RenderDocument.CURRENT_VERSION, doc.version());
    }

    // ── JSON 序列化 ──

    @Test
    @DisplayName("RenderDocument 可序列化为 JSON 和反序列化")
    void renderDocumentJsonRoundTrip() {
        PresentationPlan plan = new PresentationPlan();
        plan.setSummary("测试摘要");
        plan.setBulletItems(List.of("要点1", "要点2"));

        RenderDocument doc = assembler.assemble(plan, "run-010");
        String json = doc.toJson();
        assertNotNull(json);
        assertTrue(json.contains("run-010"));
        assertTrue(json.contains("summary"));

        RenderDocument parsed = RenderDocument.fromJson(json);
        assertEquals(doc.version(), parsed.version());
        assertEquals(doc.runId(), parsed.runId());
        assertEquals(doc.blocks().size(), parsed.blocks().size());
    }

    // ── 区块数量上限 ──

    @Test
    @DisplayName("超大计划 → 截断至 MAX_BLOCKS")
    void maxBlocksEnforced() {
        // 创建超过 MAX_BLOCKS 的计划
        analysisState.setCurrentThreadId("test-thread-max");
        for (int i = 0; i < 30; i++) {
            analysisState.addData("key_" + i,
                    "[{\"col1\":\"a\",\"col2\":1}]");
        }

        List<String> manyTables = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            manyTables.add("key_" + i);
        }
        PresentationPlan plan = new PresentationPlan();
        plan.setTableOutputKeys(manyTables);

        RenderDocument doc = assembler.assemble(plan, "run-011");
        assertNotNull(doc);
        // blocks 数不超过 MAX_BLOCKS
        assertTrue(doc.blocks().size() <= RenderDocument.MAX_BLOCKS,
                "区块数应不超过 " + RenderDocument.MAX_BLOCKS + "，实际: " + doc.blocks().size());
    }
}
