package com.AIBI.service;

import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.DataFile;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 意图规则匹配器 — 三层架构的 Layer 1。
 * <p>
 * 零模型开销，纯正则匹配，拦截问候/告别/自我介绍/文件查询/安全边界等可枚举场景。
 * 匹配成功 → 直接返回回复文案。未匹配 → 返回 empty 让上层继续。
 */
@Slf4j
@Component
public class IntentRuleMatcher {

    @Autowired
    private DataFileMapper dataFileMapper;

    // ═══════════════════════════════════════════════════════════
    // 规则定义
    // 设计原则：
    // 1. GREETING/FAREWELL — ^...$ 完全匹配 → 严，纯问候/告别才拦截
    // 2. SELF_INTRO — .find() 包含匹配 → 中，系统介绍短语不会出现在分析请求中
    // 3. FILE_QUERY — 短精确 + 安全长尾 → 严，"看看文件+内容"不误杀
    // 4. MODEL_QUERY — .find() 包含匹配 → 中
    // 5. SYSTEM_BOUNDARY — .find() 特定长模式 → 严，避免"底层数据"误杀
    // 6. VAGUE — 完全匹配极短短语 → 严
    // ═══════════════════════════════════════════════════════════

    // ── 问候 ──────────────────────────────────────────────
    // 完全匹配 ^...$，仅纯问候短消息才匹配
    private static final Pattern GREETING = Pattern.compile(
            "^(你[好您]|[你您]好?|hi|hello|嗨|hey|在吗|在不在|哈喽|嗨喽|你好吗)" +
            "\\s*[!！.。~～]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ── 告别/感谢 ─────────────────────────────────────────
    // 完全匹配 ^...$，仅纯告别/感谢消息才匹配
    private static final Pattern FAREWELL = Pattern.compile(
            "^(谢谢?|多谢|感谢|辛苦了|再见|拜拜?|bye|好的?|行|嗯|ok|okay|没问题" +
            "|[没没有]有了|暂时没有|就这些|先这样|可以了|知道了|明白了?" +
            "|好的谢谢|好的多谢|好的感谢|好嘞)" +
            "\\s*[!！.。~～]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ── 自我介绍 ──────────────────────────────────────────
    // 包含匹配 .find()，系统介绍短语不会出现在分析请求中
    private static final Pattern SELF_INTRO = Pattern.compile(
            "(你是谁|你叫什么|能做什么|你会什么|你有什么功能|介绍.*自己" +
            "|你的能力|你能帮我什么|你有哪些能力|介绍一下自己|说说你自己" +
            "|你有什么用|你擅长什么)",
            Pattern.CASE_INSENSITIVE);

    // ── 文件查询 ──────────────────────────────────────────
    // 分两级：① 精确短语 ^...$（安全） ② 特定长尾 .find()（无歧义）
    // 注意：不要把泛文件词（"显示文件""查看文件"）放 .find()，
    // 否则"查看文件的销售额"被误杀
    private static final Pattern FILE_QUERY_EXACT = Pattern.compile(
            "^(我的文件|文件列表|有什么文件|当前文件|文件管理|文件状态)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_QUERY_SAFE = Pattern.compile(
            "(有哪些文件|上传了什么|已上传的?文件|我上传过)",
            Pattern.CASE_INSENSITIVE);

    // ── 模型查询 ──────────────────────────────────────────
    // 包含匹配 .find()
    private static final Pattern MODEL_QUERY = Pattern.compile(
            "(什么模型|用的什么模型|当前模型|模型配置|api.*key|api配置|配置.*模型" +
            "|你用的什么|你的模型|当前配置|你是什么模型|切换模型|换模型)",
            Pattern.CASE_INSENSITIVE);

    // ── 安全边界 — 系统内部问题 ──────────────────────────
    // 包含匹配 .find()，但用长模式避免误杀
    // 注意：禁止用 "底层"（误杀"底层数据"）、"架构"（误杀"数据结构"）、"设计"过宽
    private static final Pattern SYSTEM_BOUNDARY = Pattern.compile(
            "(怎么实现|用什么技术|系统提示词|prompt" +
            "|agent.*原理|工具.*实现" +
            "|内部.*机制|代码.*怎么" +
            "|底层实现|底层原理|底层架构|底层代码|底层逻辑" +
            "|源码|架构设计|如何工作的|运行原理|技术架构|技术栈" +
            "|系统指令|系统配置.*怎么|你.*怎么工作的)" +
            "|你.*系统.*(指令|提示)",
            Pattern.CASE_INSENSITIVE);

    // ── 模糊需求 ──────────────────────────────────────────
    // 完全匹配 ^...$，仅极短短语
    // "看看" "分析一下" → 拦截反问
    // "看看各产品销售数据" → 长度超 → 放过进 Agent
    private static final Pattern VAGUE_ANALYSIS = Pattern.compile(
            "^(看看|查查|瞧瞧|展示|显示|看一下)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VAGUE_SHORT_PHRASE = Pattern.compile(
            "^(看看数据|查查数据|看看分析|分析一下|展示一下|显示一下" +
            "|简单看看|快速查看|随便看看)$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 流式输出配置
    // ═══════════════════════════════════════════════════════════

    public static final int STREAM_CHUNK_SIZE = 6;
    public static final long STREAM_CHUNK_DELAY_MS = 25;

    // ═══════════════════════════════════════════════════════════

    /**
     * 对用户消息进行规则匹配。
     *
     * @param message 用户原始消息
     * @return 匹配结果，empty 表示未命中规则
     */
    public Optional<IntentRuleResult> match(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String trimmed = message.trim();

        // 1. 问候
        if (GREETING.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "你好！我是 CData Agent，你的数据分析助手。上传文件后告诉我你想分析什么，我来帮你生成图表和分析结论。",
                    "嗨！有什么数据需要分析的吗？上传文件后告诉我想看什么。",
                    "你好！随时可以帮你分析数据。"
            )));
        }

        // 2. 告别/感谢
        if (FAREWELL.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "不客气！有需要随时找我。",
                    "好的，随时可以继续分析。",
                    "没问题，有新数据或新问题再来找我。"
            )));
        }

        // 3. 自我介绍
        if (SELF_INTRO.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true,
                    "我是 CData Agent，专门帮您分析 Excel/CSV 数据的 AI 助手。\n\n" +
                    "您可以：\n" +
                    "- 上传 xlsx/xls/csv 格式的数据文件\n" +
                    "- 用自然语言描述分析目标（如'各产品销售额排名'、'按月看趋势'）\n" +
                    "- 我会生成图表和分析结论\n\n" +
                    "请先上传数据文件，然后告诉我你想分析什么。"
            ));
        }

        // 4. 文件查询 — 精确短语优先，再用安全长尾
        if (FILE_QUERY_EXACT.matcher(trimmed).find()
                || FILE_QUERY_SAFE.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true, buildFileListResponse()));
        }

        // 5. 模型查询
        if (MODEL_QUERY.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true,
                    "当前使用的是您配置的自定义模型。您可以在模型配置页面查看和修改。"));
        }

        // 6. 安全边界 — 系统内部问题
        if (SYSTEM_BOUNDARY.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true,
                    "我是数据分析助手，专注于帮您分析数据。请问您有什么数据分析需求？"));
        }

        // 7. 模糊需求 — 仅极短短语（词数≤4）才拦截
        if (VAGUE_ANALYSIS.matcher(trimmed).matches()
                || VAGUE_SHORT_PHRASE.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true,
                    "请问您想看什么维度的分析？比如：各产品的销售额对比、按月份的趋势变化、或者利润排名等。"));
        }

        return Optional.empty();
    }

    // ─── 回复构建 ──────────────────────────────────────────

    private String buildFileListResponse() {
        try {
            QueryWrapper<DataFile> qw = new QueryWrapper<>();
            qw.eq("status", "READY").orderByAsc("createTime");
            List<DataFile> files = dataFileMapper.selectList(qw);
            if (files.isEmpty()) {
                return "当前没有已上传的数据文件。请先上传 Excel 或 CSV 文件。";
            }
            StringBuilder sb = new StringBuilder("当前共有 ").append(files.size()).append(" 个数据文件：\n");
            for (DataFile f : files) {
                sb.append("- ").append(f.getOriginalFilename());
                if (f.getRowCount() != null) {
                    sb.append(" (").append(f.getRowCount()).append(" 行)");
                }
                sb.append("\n");
            }
            sb.append("\n请选择要分析的文件，告诉我想看什么。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("查询文件列表失败", e);
            return "查询文件列表时遇到问题，请稍后再试。";
        }
    }

    // ─── 工具 ──────────────────────────────────────────────

    private static String pickRandom(String... options) {
        return options[(int) (System.currentTimeMillis() % options.length)];
    }

    public record IntentRuleResult(boolean matched, String reply) {}
}
