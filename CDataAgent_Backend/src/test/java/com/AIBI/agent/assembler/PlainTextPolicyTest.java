package com.AIBI.agent.assembler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlainTextPolicy 单元测试。
 */
@DisplayName("PlainTextPolicy 测试")
class PlainTextPolicyTest {

    // ── 检测测试 ──

    @Test
    @DisplayName("纯文本通过检测")
    void pureTextPasses() {
        assertFalse(PlainTextPolicy.containsPresentationalMarkdown("这是一段正常的分析总结。"));
        assertFalse(PlainTextPolicy.containsPresentationalMarkdown("2024年销售额增长20%，利润同步提升。"));
    }

    @Test
    @DisplayName("标题标记被检测")
    void headingDetected() {
        assertTrue(PlainTextPolicy.containsPresentationalMarkdown("## 分析结论"));
        assertTrue(PlainTextPolicy.containsPresentationalMarkdown("### 子标题\n内容"));
    }

    @Test
    @DisplayName("加粗标记被检测")
    void boldDetected() {
        assertTrue(PlainTextPolicy.containsPresentationalMarkdown("这是**重要**信息"));
        assertTrue(PlainTextPolicy.containsPresentationalMarkdown("__下划线加粗__"));
    }

    @Test
    @DisplayName("围栏代码块被检测")
    void fencedCodeDetected() {
        assertTrue(PlainTextPolicy.containsPresentationalMarkdown("```\ncode here\n```"));
    }

    @Test
    @DisplayName("表格分隔符被检测")
    void tableSeparatorDetected() {
        assertTrue(PlainTextPolicy.containsPresentationalMarkdown("| header1 | header2 |\n| --- | --- |"));
    }

    @Test
    @DisplayName("null 和空文本不触发检测")
    void nullAndEmptyNotDetected() {
        assertFalse(PlainTextPolicy.containsPresentationalMarkdown(null));
        assertFalse(PlainTextPolicy.containsPresentationalMarkdown(""));
        assertFalse(PlainTextPolicy.containsPresentationalMarkdown("   "));
    }

    // ── 修复测试 ──

    @Test
    @DisplayName("标题标记修复成功")
    void headingRepaired() {
        String result = PlainTextPolicy.attemptRepair("## 分析结论\n这是一段内容。");
        assertNotNull(result);
        assertFalse(result.contains("##"));
        assertTrue(result.contains("分析结论"));
    }

    @Test
    @DisplayName("加粗标记修复成功")
    void boldRepaired() {
        String result = PlainTextPolicy.attemptRepair("这是**重要**的信息");
        assertNotNull(result);
        assertFalse(result.contains("**"));
        assertTrue(result.contains("重要"));
    }

    @Test
    @DisplayName("表格分隔符导致修复失败")
    void tableNotRepairable() {
        String result = PlainTextPolicy.attemptRepair("| a | b |\n| --- | --- |\n| 1 | 2 |");
        assertNull(result); // 修复应失败，因为表格分隔符无法安全去除
    }

    @Test
    @DisplayName("纯文本无需修复")
    void plainTextNoRepairNeeded() {
        String result = PlainTextPolicy.attemptRepair("正常文本无需修复");
        assertEquals("正常文本无需修复", result);
    }

    // ── 降级测试 ──

    @Test
    @DisplayName("降级文本去除所有标记")
    void degradedTextCleaned() {
        String result = PlainTextPolicy.degradedText("## 标题\n**重点**\n| 表格 |", "test");
        assertNotNull(result);
        assertFalse(result.contains("##"));
        assertFalse(result.contains("**"));
        assertFalse(result.contains("| 表格 |"));
    }

    @Test
    @DisplayName("空文本降级返回占位文本")
    void emptyDegradedText() {
        String result = PlainTextPolicy.degradedText("", "reason");
        assertNotNull(result);
        assertTrue(result.contains("无法解析"));
    }

    @Test
    @DisplayName("null 降级返回占位文本")
    void nullDegradedText() {
        String result = PlainTextPolicy.degradedText(null, "reason");
        assertNotNull(result);
        assertTrue(result.contains("无法解析"));
    }

    // ── validate 完整流程 ──

    @Test
    @DisplayName("纯文本 validate 通过")
    void validatePureText() {
        PlainTextPolicy.PlainTextResult result = PlainTextPolicy.validate("正常文本", "test");
        assertTrue(result.isOk());
        assertEquals("正常文本", result.text());
    }

    @Test
    @DisplayName("含 Markdown 文本 validate 修复或降级")
    void validateMarkdownText() {
        PlainTextPolicy.PlainTextResult result = PlainTextPolicy.validate("## 标题内容", "test");
        // 修复成功或降级，总之不能含标题标记
        assertFalse(result.text().contains("##"));
    }

    // ── 列表检测 ──

    @Test
    @DisplayName("列表中的 Markdown 被检测")
    void listMarkdownDetected() {
        assertTrue(PlainTextPolicy.anyContainsMarkdown(List.of("正常", "**异常**")));
        assertFalse(PlainTextPolicy.anyContainsMarkdown(List.of("正常1", "正常2")));
    }

    @Test
    @DisplayName("空列表和 null 不触发检测")
    void nullListNotDetected() {
        assertFalse(PlainTextPolicy.anyContainsMarkdown(null));
        assertFalse(PlainTextPolicy.anyContainsMarkdown(List.of()));
    }
}
