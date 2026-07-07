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
 * 意图规则匹配器 — 零模型开销的 Layer 1 规则引擎。
 * <p>
 * 纯正则匹配，拦截所有无需 LLM 的场景。所有模式均是 ^...$ 精确匹配（标注外），
 * 多一个字符即不命中，绝对安全，不会误杀分析请求。
 * <p>
 * 分类（匹配顺序自上而下）：
 * <ol>
 *   <li>GREETING — 问候</li>
 *   <li>FAREWELL — 告别/结束</li>
 *   <li>GRATITUDE — 感谢</li>
 *   <li>ACKNOWLEDGE — 简单确认</li>
 *   <li>EVALUATE — 简短评价</li>
 *   <li>COMPLETION — 完成/明白</li>
 *   <li>SELF_INTRO — 自我介绍</li>
 *   <li>FILE_QUERY — 文件查询</li>
 *   <li>MODEL_QUERY — 模型查询</li>
 *   <li>SYSTEM_BOUNDARY — 安全边界</li>
 *   <li>VAGUE — 模糊需求</li>
 * </ol>
 */
@Slf4j
@Component
public class IntentRuleMatcher {

    @Autowired
    private DataFileMapper dataFileMapper;

    // ═══════════════════════════════════════════════════════════
    // 1. 问候
    // 完全匹配 ^...$，仅纯问候短消息才匹配
    // ═══════════════════════════════════════════════════════════

    private static final Pattern GREETING = Pattern.compile(
            "^(你[好您]|[你您]好?|hi|hello|嗨|hey|在吗|在不在|哈喽|嗨喽|你好吗)" +
            "\\s*[!！.。~～]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 2. 告别/结束 — 明确表示对话已结束
    // 完全匹配 ^...$，不含感谢/确认/评价
    // ═══════════════════════════════════════════════════════════

    private static final Pattern FAREWELL = Pattern.compile(
            "^(再见|拜拜?|bye|好嘞" +
            "|[没没有]有了|没了|暂时没有|就这些|先这样" +
            "|没什么了|今天就到这|下次再聊)" +
            "\\s*[!！.。~～]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 3. 感谢 — 纯感谢，不含告别/确认语义
    // 完全匹配 ^...$
    // ═══════════════════════════════════════════════════════════

    private static final Pattern GRATITUDE = Pattern.compile(
            "^(谢谢?|多谢|感谢|辛苦了" +
            "|好的谢谢|好的多谢|好的感谢)" +
            "\\s*[!！.。~～]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 4. 简单确认 — 用户表示收到/知道了/同意
    // 完全匹配 ^...$，回复保持对话打开
    // ═══════════════════════════════════════════════════════════

    private static final Pattern ACKNOWLEDGE = Pattern.compile(
            "^(嗯{1,3}" +
            "|好[的滴哒呀]?" +
            "|[哦噢]{1,2}" +
            "|行|ok|okay|好吧" +
            "|了解|收到|明白|没问题" +
            "|可以了)" +
            "\\s*[!！.。~～]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 5. 简短评价 — 用户对分析结果的反馈
    // 完全匹配 ^...$，不会误杀"不错的数据"等分析句
    // ═══════════════════════════════════════════════════════════

    private static final Pattern EVALUATE = Pattern.compile(
            "^(不错|可以|还行|很好" +
            "|有意思|挺有意思|有趣|挺有趣)" +
            "\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 6. 完成/明白 — 用户表明已看完/理解了
    // 完全匹配 ^...$
    // ═══════════════════════════════════════════════════════════

    private static final Pattern COMPLETION = Pattern.compile(
            "^(看[到完]了" +
            "|懂了" +
            "|明白了?|知道了)" +
            "\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 7. 自我介绍
    // 包含匹配 .find()
    // ═══════════════════════════════════════════════════════════

    private static final Pattern SELF_INTRO = Pattern.compile(
            "(你是谁|你叫什么|能做什么|你会什么|你有什么功能|介绍.*自己" +
            "|你的能力|你能帮我什么|你有哪些能力|介绍一下自己|说说你自己" +
            "|你有什么用|你擅长什么)",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 8. 文件查询
    // 分两级：精确短语 ^...$ + 安全长尾 .find()
    // ═══════════════════════════════════════════════════════════

    private static final Pattern FILE_QUERY_EXACT = Pattern.compile(
            "^(我的文件|文件列表|有什么文件|当前文件|文件管理|文件状态)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_QUERY_SAFE = Pattern.compile(
            "(有哪些文件|上传了什么|已上传的?文件|我上传过)",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 9. 模型查询
    // 包含匹配 .find()
    // ═══════════════════════════════════════════════════════════

    private static final Pattern MODEL_QUERY = Pattern.compile(
            "(什么模型|用的什么模型|当前模型|模型配置|api.*key|api配置|配置.*模型" +
            "|你用的什么|你的模型|当前配置|你是什么模型|切换模型|换模型)",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 10. 安全边界 — 系统内部问题
    // 包含匹配 .find()，用长模式避免误杀
    // ═══════════════════════════════════════════════════════════

    private static final Pattern SYSTEM_BOUNDARY = Pattern.compile(
            "(怎么实现|用什么技术|系统提示词|prompt" +
            "|agent.*原理|工具.*实现" +
            "|内部.*机制|代码.*怎么" +
            "|底层实现|底层原理|底层架构|底层代码|底层逻辑" +
            "|源码|架构设计|如何工作的|运行原理|技术架构|技术栈" +
            "|系统指令|系统配置.*怎么|你.*怎么工作的)" +
            "|你.*系统.*(指令|提示)",
            Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════
    // 11. 模糊需求 — 仅极短短语
    // 完全匹配 ^...$，仅词数≤4 的短词才拦截
    // ═══════════════════════════════════════════════════════════

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

    public static final int STREAM_CHUNK_SIZE = 4;
    public static final long STREAM_CHUNK_DELAY_MS = 50;

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

        // ── 1. 问候 ──────────────────────────────────────
        if (GREETING.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "你好！我是 CData Agent，你的数据分析助手。上传文件后告诉我你想分析什么，我来帮你生成图表和分析结论。",
                    "嗨！有什么数据需要分析的吗？上传文件后告诉我想看什么。",
                    "你好！随时可以帮你分析数据。"
            )));
        }

        // ── 2. 告别/结束 ─────────────────────────────────
        if (FAREWELL.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "好的，有需要随时找我。",
                    "没问题，有新数据或新问题再来。",
                    "行，下次需要分析再来找我。"
            )));
        }

        // ── 3. 感谢 ──────────────────────────────────────
        if (GRATITUDE.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "不客气！还有什么需要进一步分析的吗？",
                    "客气啦，有需要继续问。",
                    "不客气！还有其他想分析的吗？"
            )));
        }

        // ── 4. 简单确认 ─────────────────────────────────
        if (ACKNOWLEDGE.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "好的，还需要看什么分析吗？",
                    "嗯嗯，有想继续看的方向吗？",
                    "收到，还有什么想深入分析的？"
            )));
        }

        // ── 5. 简短评价 ─────────────────────────────────
        if (EVALUATE.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "谢谢！还想从其他维度看看吗？",
                    "谢谢！需要继续深入分析某个点吗？",
                    "谢谢！有想进一步看的方面吗？"
            )));
        }

        // ── 6. 完成/明白 ────────────────────────────────
        if (COMPLETION.matcher(trimmed).matches()) {
            return Optional.of(new IntentRuleResult(true, pickRandom(
                    "好的。如果想继续分析其他方向，随时告诉我。",
                    "明白。还有别的维度想看吗？",
                    "好的，还想继续分析别的内容吗？"
            )));
        }

        // ── 7. 自我介绍 ──────────────────────────────────
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

        // ── 8. 文件查询 ──────────────────────────────────
        if (FILE_QUERY_EXACT.matcher(trimmed).find()
                || FILE_QUERY_SAFE.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true, buildFileListResponse()));
        }

        // ── 9. 模型查询 ──────────────────────────────────
        if (MODEL_QUERY.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true,
                    "当前使用的是您配置的自定义模型。您可以在模型配置页面查看和修改。"));
        }

        // ── 10. 安全边界 — 系统内部问题 ─────────────────
        if (SYSTEM_BOUNDARY.matcher(trimmed).find()) {
            return Optional.of(new IntentRuleResult(true,
                    "我是数据分析助手，专注于帮您分析数据。请问您有什么数据分析需求？"));
        }

        // ── 11. 模糊需求 — 仅极短短语（词数≤4）才拦截 ──
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
