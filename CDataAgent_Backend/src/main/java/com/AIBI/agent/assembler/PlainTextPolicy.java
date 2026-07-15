package com.AIBI.agent.assembler;

import com.AIBI.agent.model.RenderDocument;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本安全策略 — 检测和修复 LLM 输出中的展示性 Markdown。
 * <p>
 * 目标：文本字段只允许纯文本，不允许包含标题、围栏代码块、表格、加粗等
 * 展示性 Markdown 标记。如果检测到，进行一次受控修复；修复仍失败则生成确定性降级文本。
 * <p>
 * 注意：单字符的 {@code *}（如 "A*B" 中的星号）和连字符 {@code -}
 * 可能是自然语言的一部分，不在检测范围。只检测明确的 Markdown 展示性模式。
 */
@Slf4j
public final class PlainTextPolicy {

    private PlainTextPolicy() {
        // 工具类，禁止实例化
    }

    // ── 检测模式 ─────────────────────────────────────────────

    /** 标题：行首 # 后跟空格（如 ## 标题、### 子标题） */
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+", Pattern.MULTILINE);

    /** 围栏代码块：多行 ``` 包裹 */
    private static final Pattern FENCED_CODE = Pattern.compile("```[\\s\\S]*?```", Pattern.MULTILINE);

    /** 表格分隔行：| --- | --- | 样式（表头分隔符） */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[\\s:\\|-]+\\|\\s*$", Pattern.MULTILINE);

    /** 表格数据行：以 | 开头的行 */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|.+\\|\\s*$", Pattern.MULTILINE);

    /** 加粗：** 或 __ 包裹 */
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");

    /** 围栏标记：允许语言标识，如 ```json */
    private static final Pattern FENCE_MARKER = Pattern.compile("^```[^\\r\\n]*$", Pattern.MULTILINE);

    /** 无序列表标记：行首 - 或 * 后跟空格 */
    private static final Pattern LIST_MARKER = Pattern.compile("^[*-]\\s+", Pattern.MULTILINE);

    /** 有序列表标记：行首数字后跟 . 和空格 */
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\d+\\.\\s+", Pattern.MULTILINE);

    // ── 公开方法 ─────────────────────────────────────────────

    /**
     * 检测文本是否包含展示性 Markdown。
     *
     * @param text 待检测文本
     * @return true 如果包含任何展示性 Markdown 模式
     */
    public static boolean containsPresentationalMarkdown(String text) {
        if (text == null || text.isBlank()) return false;
        return HEADING.matcher(text).find()
                || FENCED_CODE.matcher(text).find()
                || TABLE_SEPARATOR.matcher(text).find()
                || BOLD.matcher(text).find()
                || FENCE_MARKER.matcher(text).find()
                || LIST_MARKER.matcher(text).find()
                || ORDERED_LIST.matcher(text).find();
    }

    /**
     * 检测文本列表中的每一项是否包含展示性 Markdown。
     *
     * @param items 文本列表
     * @return true 如果任意一项包含展示性 Markdown
     */
    public static boolean anyContainsMarkdown(List<String> items) {
        if (items == null || items.isEmpty()) return false;
        return items.stream().anyMatch(PlainTextPolicy::containsPresentationalMarkdown);
    }

    /**
     * 受控修复：去除展示性 Markdown 标记。
     * <p>
     * 只尝试一次。修复策略：去除标题、围栏、加粗、列表标记，并将表格压平为文本。
     *
     * @param text 原始文本
     * @return 修复后的纯文本，如果修复后仍包含 Markdown 则返回 null
     */
    public static String attemptRepair(String text) {
        if (text == null || text.isBlank()) return text;

        String repaired = text;

        // 1. 围栏内容可能是 JSON、代码或工具参数，不能进入展示文本
        repaired = removeFencedContent(repaired);

        // 2. 去除标题标记（保留标题文字）
        repaired = HEADING.matcher(repaired).replaceAll("");

        // 3. 去除加粗标记（保留内部文字）
        repaired = BOLD.matcher(repaired).replaceAll("$1$2");

        // 4. 去除列表标记
        repaired = LIST_MARKER.matcher(repaired).replaceAll("");
        repaired = ORDERED_LIST.matcher(repaired).replaceAll("");

        // 5. 将 Markdown 表格压平为纯文本行，避免有效业务内容被整体降级
        repaired = flattenTableRows(repaired);

        // 6. 去除空行
        repaired = repaired.replaceAll("\\n{3,}", "\n\n").trim();

        // 7. 如果修复后仍有展示性 Markdown，交由确定性降级策略处理
        if (containsPresentationalMarkdown(repaired)) {
            return null;
        }

        return repaired;
    }

    /**
     * 生成确定性降级文本（修复失败时的最终兜底方案）。
     * <p>
     * 激进去除所有可能的 Markdown 标记，并限制长度。
     *
     * @param text   原始文本
     * @param reason 降级原因
     * @return 安全的纯文本
     */
    public static String degradedText(String text, String reason) {
        if (text == null || text.isBlank()) {
            return "（内容无法解析：" + reason + "）";
        }

        // 激进清理：去除所有特殊字符
        String cleaned = text
                .replaceAll("#{1,6}\\s*", "")       // 标题
                .replaceAll("\\*\\*|__", "")          // 加粗
                .replaceAll("```[\\s\\S]*?```", "")   // 围栏代码块
                .replaceAll("```", "")                 // 围栏标记
                .replaceAll("(?m)^[*-]\\s+", "")      // 无序列表
                .replaceAll("(?m)^\\d+\\.\\s+", "")   // 有序列表
                .replaceAll("\\|", " ")                // 表格列分隔符
                .replaceAll("(?m)^[-|]+$", "")        // 表格分隔行
                .replaceAll("\\n{3,}", "\n\n")        // 多余空行
                .trim();

        if (cleaned.isBlank()) {
            return "（内容无法解析：" + reason + "）";
        }

        // 限制降级文本长度
        if (cleaned.length() > com.AIBI.agent.model.RenderDocument.MAX_TEXT_LENGTH) {
            cleaned = cleaned.substring(0, com.AIBI.agent.model.RenderDocument.MAX_TEXT_LENGTH - 3) + "...";
        }

        return cleaned;
    }

    /**
     * 验证并修复文本：先检测 → 如有问题尝试修复 → 修复失败则降级。
     *
     * @param text   原始文本
     * @param reason 用于降级日志的上下文描述
     * @return PlainTextResult 包含最终文本和是否降级
     */
    public static PlainTextResult validate(String text, String reason) {
        if (text == null || text.isBlank()) {
            return new PlainTextResult("", false);
        }

        if (!containsPresentationalMarkdown(text)) {
            // 无展示性 Markdown，直接通过
            String trimmed = text.length() > com.AIBI.agent.model.RenderDocument.MAX_TEXT_LENGTH
                    ? text.substring(0, com.AIBI.agent.model.RenderDocument.MAX_TEXT_LENGTH - 3) + "..."
                    : text;
            return new PlainTextResult(trimmed, false);
        }

        // 检测到 Markdown，尝试修复
        String repaired = attemptRepair(text);
        if (repaired != null) {
            log.debug("文本已规范化：原因={}、长度={}=>{}", reason, text.length(), repaired.length());
            String trimmed = repaired.length() > com.AIBI.agent.model.RenderDocument.MAX_TEXT_LENGTH
                    ? repaired.substring(0, com.AIBI.agent.model.RenderDocument.MAX_TEXT_LENGTH - 3) + "..."
                    : repaired;
            return new PlainTextResult(trimmed, false);
        }

        // 修复失败，降级
        String degraded = degradedText(text, reason);
        log.warn("文本已降级：原因={}、长度={}=>{}", reason, text.length(), degraded.length());
        return new PlainTextResult(degraded, true);
    }

    private static String flattenTableRows(String text) {
        Matcher matcher = TABLE_ROW.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String row = matcher.group();
            String replacement = TABLE_SEPARATOR.matcher(row).matches()
                    ? ""
                    : row.replaceFirst("^\\|", "")
                            .replaceFirst("\\|\\s*$", "")
                            .replaceAll("\\s*\\|\\s*", "；");
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String removeFencedContent(String text) {
        StringBuilder output = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                if (!output.isEmpty()) output.append('\n');
                output.append(line);
            }
        }
        return output.toString();
    }

    /**
     * PlainTextPolicy 的验证结果。
     */
    public record PlainTextResult(String text, boolean degraded) {
        public boolean isOk() { return !degraded; }
    }
}
