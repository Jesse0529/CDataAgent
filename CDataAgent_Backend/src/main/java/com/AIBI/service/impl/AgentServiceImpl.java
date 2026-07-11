package com.AIBI.service.impl;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.common.ErrorCode;
import com.AIBI.exception.BusinessException;
import com.AIBI.exception.ThrowUtils;
import com.AIBI.config.ExactTokenCounter;
import com.AIBI.config.ModelManager;
import com.AIBI.config.TtlRedisSaver;
import com.AIBI.config.DuckDbConnectionManager;
import com.AIBI.manager.RedisLimiterManager;
import com.AIBI.manager.TokenLedger;
import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.Conversation;
import com.AIBI.model.entity.ConversationMessage;
import com.AIBI.model.entity.DataFile;
import com.AIBI.model.vo.MessageVO;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.AIBI.service.AgentService;
import com.AIBI.service.ConversationMessageService;
import com.AIBI.service.ConversationService;
import com.AIBI.service.IntentRuleMatcher;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent 对话服务实现 — 单对话模式，Executor 自主决策 + Synthesizer 图表合成。
 */
@Slf4j
@Service
public class AgentServiceImpl implements AgentService {

    @Autowired
    @Qualifier("executorAgent")
    private ReactAgent executorAgent;

    @Autowired
    @Qualifier("synthesizerAgent")
    private ReactAgent synthesizerAgent;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMessageService conversationMessageService;

    @Lazy
    @Autowired
    private RedisLimiterManager redisLimiterManager;

    @Autowired
    private BaseCheckpointSaver saver;

    @Autowired
    private TtlRedisSaver ttlRedisSaver;

    @Autowired
    private AnalysisState analysisState;

    @Autowired
    private DataFileMapper dataFileMapper;

    @Autowired
    private com.AIBI.manager.UserPreferenceManager userPreferenceManager;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private IntentRuleMatcher intentRuleMatcher;

    @Autowired
    private TokenLedger tokenLedger;

    @Autowired
    private ModelManager modelManager;

    @Autowired
    private ExactTokenCounter exactTokenCounter;

    @Autowired
    private DuckDbConnectionManager duckDbConnectionManager;

    @Value("${agent.global-timeout-seconds:300}")
    private int agentTimeoutSeconds;

    @Value("${agent.context.inject-analysis-state:true}")
    private boolean injectAnalysisState;

    private static final String LOCK_PREFIX = "agent:lock:";
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 350;
    private static final int MAX_CONVERSATION_ROUNDS = 50;

    /** 结论标记正则：##CONCLUSION##\n...\n##END##（兼容 CRLF \r\n） */
    private static final Pattern CONCLUSION_PATTERN = Pattern.compile(
            "##CONCLUSION##\\r?\\n([\\s\\S]*?)\\r?\\n##END##", Pattern.MULTILINE);

    /** 默认对话 ID 缓存（首次访问时初始化） */
    private volatile Long defaultConversationId;

    /** 上一轮 AI 回复的图表配置 JSON，由 doOnComplete 写入、Controller 读取 */
    private volatile String lastChartOption;

    /** 上一轮 AI 回复的 token 消耗量，由 doOnComplete 写入、Controller 读取 */
    private volatile Integer lastTokenUsage;

    @Override
    public synchronized Long getOrCreateDefaultConversation() {
        if (defaultConversationId != null) return defaultConversationId;
        // 查找已有对话，取第一个
        List<Conversation> existing = conversationService.list(
                new QueryWrapper<Conversation>().orderByAsc("createTime").last("LIMIT 1"));
        if (!existing.isEmpty()) {
            defaultConversationId = existing.get(0).getId();
            return defaultConversationId;
        }
        // 不存在则创建
        Conversation conv = new Conversation();
        conv.setTitle("默认对话");
        conversationService.save(conv);
        defaultConversationId = conv.getId();
        log.info("默认对话已创建: id={}", defaultConversationId);
        return defaultConversationId;
    }

    @Override
    public String getLastChartOption() {
        return lastChartOption;
    }

    @Override
    public Integer getLastTokenUsage() {
        return lastTokenUsage;
    }

    @Override
    public void deleteMessages(Long conversationId) {
        ThrowUtils.throwIf(conversationId == null, ErrorCode.PARAMS_ERROR, "对话ID不能为空");
        // 校验对话是否存在
        Conversation conv = conversationService.getById(conversationId);
        ThrowUtils.throwIf(conv == null, ErrorCode.NOT_FOUND_ERROR, "对话不存在");

        conversationMessageService.remove(
                new QueryWrapper<ConversationMessage>().eq("conversationId", conversationId));
        log.info("对话 {} 消息已清空", conversationId);
    }

    @Override
    public void resetConversation(Long conversationId) {
        // ── 参数校验 ──
        ThrowUtils.throwIf(conversationId == null, ErrorCode.PARAMS_ERROR, "对话ID不能为空");
        Conversation conv = conversationService.getById(conversationId);
        ThrowUtils.throwIf(conv == null, ErrorCode.NOT_FOUND_ERROR, "对话不存在");

        // ── 获取对话锁，防止与活跃流冲突 ──
        RLock lock = redissonClient.getLock(LOCK_PREFIX + conversationId);
        try {
            // 0-wait: 锁被持有说明有活跃对话，直接拒绝
            if (!lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                        "对话正在进行中，无法重置");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙");
        }

        try {
            // Step 1: 删除 H2 消息
            conversationMessageService.remove(
                    new QueryWrapper<ConversationMessage>().eq("conversationId", conversationId));
            log.debug("Step 1/6: H2 messages deleted: cid={}", conversationId);

            // Step 2: 清空 AnalysisState
            analysisState.resetByConversation(conversationId.toString());
            log.debug("Step 2/6: AnalysisState cleared: cid={}", conversationId);

            // Step 3: 重置 TokenLedger
            tokenLedger.reset(conversationId);
            log.debug("Step 3/6: TokenLedger reset: cid={}", conversationId);

            // Step 4: 删除 Redis checkpoint key
            ttlRedisSaver.deleteCheckpoints(conversationId.toString());
            log.debug("Step 4/6: Redis checkpoints deleted: cid={}", conversationId);

            // Step 5: 清空 defaultConversationId 缓存
            if (Long.valueOf(conversationId).equals(defaultConversationId)) {
                defaultConversationId = null;
                log.debug("Step 5/6: defaultConversationId cache cleared");
            }

            // Step 6: 清空 lastChartOption / lastTokenUsage
            lastChartOption = null;
            lastTokenUsage = null;
            log.debug("Step 6/6: lastChartOption / lastTokenUsage cache cleared");

            log.info("对话已完全重置: id={}, title={}", conversationId, conv.getTitle());
        } catch (Exception e) {
            log.error("重置对话异常: cid={}", conversationId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重置对话失败: " + e.getMessage());
        } finally {
            // 释放锁
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.forceUnlock();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ======================== 核心编排 ========================

    @Override
    public Flux<Map<String, String>> chatStream(String userMessage) {
        return chatStream(userMessage, null);
    }

    @Override
    public Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds) {
        ThrowUtils.throwIf(StringUtils.isBlank(userMessage), ErrorCode.PARAMS_ERROR, "消息不能为空");

        return Flux.defer(() -> {
            // Layer 1: 规则层 — 零模型开销拦截问候/告别/文件查询等
            Optional<IntentRuleMatcher.IntentRuleResult> ruleOpt = intentRuleMatcher.match(userMessage);
            if (ruleOpt.isPresent()) {
                IntentRuleMatcher.IntentRuleResult ruleResult = ruleOpt.get();
                log.debug("规则层匹配: message={}, reply={}", userMessage, ruleResult.reply());
                // 规则层返回前清除上一轮的图表结论和token消耗，防止泄漏到规则回复中
                lastChartOption = null;
                lastTokenUsage = null;
                return Flux.fromIterable(splitIntoChunks(ruleResult.reply()))
                        .delayElements(Duration.ofMillis(IntentRuleMatcher.STREAM_CHUNK_DELAY_MS))
                        .map(chunk -> Map.of("type", "message", "data", normalizeTableFormat(chunk)));
            }

            Long cid = getOrCreateDefaultConversation();

            RLock lock = redissonClient.getLock(LOCK_PREFIX + cid);
            try {
                if (!lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                    return Flux.error(new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                            "对话处理中，请稍后再试"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙"));
            }

            // 设置本轮 active 文件（前端传递的文件绑定）
            analysisState.setCurrentThreadId(cid.toString());
            String fileAttachments = null;
            List<String> fileNames = new ArrayList<>();
            if (fileIds != null && !fileIds.isEmpty()) {
                analysisState.setActiveFileIds(fileIds);
                // 文件切换时清空旧加载缓存，下次 loadData 重新加载
                analysisState.setLoadedFiles(new ArrayList<>());

                // 构建文件附件 JSON（用于持久化到消息表）
                List<DataFile> dataFiles = dataFileMapper.selectBatchIds(fileIds);
                JSONArray arr = new JSONArray();
                for (DataFile df : dataFiles) {
                    JSONObject item = new JSONObject();
                    item.put("id", df.getId().toString());
                    item.put("name", df.getOriginalFilename());
                    arr.add(item);
                    fileNames.add(df.getOriginalFilename());
                }
                if (!arr.isEmpty()) {
                    fileAttachments = arr.toJSONString();
                }
            }

            saveMessage(cid, "user", userMessage, fileAttachments);
            redisLimiterManager.doRateLimit("agent_chat_default");

            String effectiveMessage = injectContext(userMessage, cid, fileIds);

            log.info("Agent 对话开始: cid={}, files={}", cid, fileNames);

            modelManager.setCurrentConversationId(cid);
            exactTokenCounter.setCurrentConversationId(cid);

            return executePipeline(cid, effectiveMessage, userMessage, lock, fileAttachments);
        });
    }

    private Flux<Map<String, String>> executePipeline(Long cid, String effectiveMessage,
                                                       String originalMessage, RLock lock,
                                                       String fileAttachments) {
        RunnableConfig config = RunnableConfig.builder().threadId(cid.toString()).build();
        Duration totalTimeout = Duration.ofSeconds(agentTimeoutSeconds);
        StringBuilder fullResponse = new StringBuilder();

        Flux<String> executorFlux;
        try {
            executorFlux = executorAgent.streamMessages(effectiveMessage, config)
                    .filter(m -> m instanceof org.springframework.ai.chat.messages.AssistantMessage)
                    .map(m -> {
                        String text = ((org.springframework.ai.chat.messages.AssistantMessage) m).getText();
                        return text != null ? text : "";
                    })
                    .filter(StringUtils::isNotBlank);
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException e) {
            executorFlux = Flux.just("【系统】执行器启动失败: " + e.getMessage());
        }

        return executorFlux
                .take(totalTimeout)
                .doOnNext(fullResponse::append)
                .onErrorResume(Exception.class, e -> {
                    log.error("Agent 执行异常, cid={}", cid, e);
                    // 如果是 DeepSeek API 返回的错误，打印响应体
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                        var wce = (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                        log.warn("DeepSeek API 错误响应体: status={}, body={}",
                                wce.getStatusCode(), wce.getResponseBodyAsString());
                    }
                    String errMsg = "【系统】处理失败: " + e.getMessage();
                    fullResponse.append(errMsg);
                    return Flux.just(errMsg);
                })
                .flatMap(token -> {
                    // 过滤内部标记：使用正则避免因 token 截断漏过
                    String clean = token
                            .replaceAll("#+NEEDS_CHART#*", "")
                            .replace("【【【【【", "")
                            .replace("】】】】】", "")
                            .trim();
                    if (clean.isEmpty()) return Flux.empty();
                    return Flux.just(Map.of("type", "message", "data", normalizeTableFormat(clean)));
                })
                .concatWith(Flux.defer(() -> {
                    String response = fullResponse.toString().trim();
                    if (!response.contains("##NEEDS_CHART##")) {
                        return Flux.empty();
                    }
                    log.info("检测到 ##NEEDS_CHART##，触发 Synthesizer: cid={}", cid);
                    String cleanText = response.replace("##NEEDS_CHART##", "").trim();
                    String synthPrompt = "用户请求: " + originalMessage + "\n\n分析结果:\n" + cleanText +
                            "\n\n请基于以上分析结果生成图表配置。";

                    // 准备异步图表信号：ChartOutputTool 完成时触发
                    java.util.concurrent.CompletableFuture<String> chartFuture = analysisState.getChartReadyFuture();

                    // 图表事件 30s 超时，防止 ChartOutputTool 未运行时管道挂起
                    reactor.core.publisher.Flux<Map<String, String>> chartSignal =
                            reactor.core.publisher.Mono.fromFuture(chartFuture).flux()
                                    .timeout(java.time.Duration.ofSeconds(30))
                                    .onErrorComplete()
                                    .map(chartJson -> Map.of("type", "chart", "data", chartJson));

                    return Flux.merge(
                            // 1) 状态事件
                            Flux.just(Map.of("type", "status", "data", "正在生成图表…")),
                            // 2) Synthesizer 仅在后台执行图表生成，文字不推给前端
                            synthesizePhase(synthPrompt, config)
                                    .doOnNext(token -> log.debug("Synthesizer 文字已丢弃: cid={}", cid))
                                    .thenMany(Flux.empty()),
                            // 3) 异步图表事件（ChartOutputTool 完成时发射）
                            chartSignal
                                    .doOnNext(chartEvent -> log.debug("图表事件已异步发射: cid={}", cid))
                    );
                }))
                .doOnComplete(() -> {
                    String raw = fullResponse.toString().trim()
                            .replaceAll("#+NEEDS_CHART#*", "")
                            .replace("【【【【【", "")
                            .replace("】】】】】", "")
                            .trim();
                    // 提取结论并去除标记
                    String conclusion = extractConclusion(raw);
                    String content = normalizeTableFormat(
                            conclusion != null ? removeConclusionMarkers(raw) : raw);
                    String chartOption = analysisState.consumeChartOptions();
                    if (!content.isEmpty()) {
                        // 从 TokenLedger 获取本轮精确 token 消耗（非字符估算）
                        int tokenUsage = Math.toIntExact(tokenLedger.consumeRoundUsage(cid)
                                .map(TokenLedger.RoundTokenUsage::total)
                                .orElse(0L));
                        saveMessage(cid, "assistant", content, fileAttachments, chartOption, tokenUsage, conclusion);
                        lastChartOption = chartOption;
                        lastTokenUsage = tokenUsage;
                        log.info("对话消息持久化成功: cid={}, hasChart={}, tokens={}, hasConclusion={}",
                                cid, chartOption != null, tokenUsage, conclusion != null);
                    }
                })
                .doFinally(signal -> {
                    String cidStr = cid.toString();
                    analysisState.clear();
                    duckDbConnectionManager.close(cidStr);
                    modelManager.clearCurrentConversationId();
                    exactTokenCounter.clear();
                    try {
                        lock.forceUnlock();
                    } catch (Exception ignored) {}
                });
    }

    private Flux<String> synthesizePhase(String prompt, RunnableConfig config) {
        log.info("Phase 3: Synthesizer 开始");
        return Flux.defer(() -> {
            reactor.core.publisher.Flux<org.springframework.ai.chat.messages.Message> flux;
            try {
                flux = synthesizerAgent.streamMessages(prompt, config);
            } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException e) {
                return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "Synthesizer 启动失败: " + e.getMessage()));
            }
            return flux.filter(m -> m instanceof org.springframework.ai.chat.messages.AssistantMessage)
                    .map(m -> {
                        String text = ((org.springframework.ai.chat.messages.AssistantMessage) m).getText();
                        return text != null ? text : "";
                    })
                    .filter(StringUtils::isNotBlank);
        });
    }

    // ======================== 上下文注入 ========================

    private String injectContext(String userMessage, Long conversationId, List<Long> fileIds) {
        StringBuilder prefix = new StringBuilder();

        if (injectAnalysisState) {
            // 仅当本轮没有新文件附件时，才从 Redis 恢复上一轮的分析状态
            // 如果有新文件（fileIds 非空），说明用户在切换/重新选择文件，
            // 此时应使用干净的上下文，让 Agent 从 loadData 开始重新加载
            boolean hasNewFiles = fileIds != null && !fileIds.isEmpty();
            if (!hasNewFiles) {
                boolean restored = analysisState.restoreFromRedis(conversationId.toString());
                if (restored) {
                    log.debug("injectContext: 已恢复上一轮分析状态, cid={}", conversationId);
                }
            } else {
                log.debug("injectContext: 本轮绑定新文件 ({} 个), 跳过状态恢复, cid={}",
                        fileIds.size(), conversationId);
            }

            String state = analysisState.toContextString();
            if (state != null && !state.isBlank()) {
                prefix.append(state).append("\n\n");
            }
        }

        // 仅当本轮有指定文件时，才主动注入文件上下文
        if (fileIds != null && !fileIds.isEmpty()) {
            String fileInfo = buildFileContext(conversationId, fileIds);
            if (fileInfo != null) {
                prefix.append("【⚠️ 当前查询仅可使用以下文件，请勿参考历史对话中的文件信息】\n");
                prefix.append(fileInfo).append("\n");
            }
        }
        // 无 fileIds 时不注入文件上下文，agent 依赖已有 loadedFiles（由 loadData 管理）

        String prefInfo = buildPreferenceContext();
        if (prefInfo != null) {
            prefix.append(prefInfo).append("\n");
        }

        if (prefix.length() > 0) {
            return prefix + "---\n用户消息: " + userMessage;
        }
        return userMessage;
    }

    /**
     * 根据指定的 fileIds 查询文件信息。
     * 只返回前端明确选择的文件，避免多文件时 agent 选错表。
     */
    private String buildFileContext(Long conversationId, List<Long> fileIds) {
        if (conversationId == null || fileIds == null || fileIds.isEmpty()) return null;
        try {
            QueryWrapper<DataFile> qw = new QueryWrapper<>();
            qw.in("id", fileIds).eq("status", "READY");
            List<DataFile> files = dataFileMapper.selectList(qw);
            if (files.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("[本消息绑定 ").append(files.size()).append(" 个数据文件]\n");
            for (DataFile f : files) {
                sb.append("  fileId=").append(f.getId())
                        .append(" | ").append(f.getOriginalFilename())
                        .append(" | viewName=").append(f.getViewName())
                        .append(" | ").append(f.getRowCount()).append("行\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("buildFileContext 查询失败", e);
            return null;
        }
    }

    private String buildPreferenceContext() {
        try {
            java.util.Map<String, String> prefs = userPreferenceManager.getAllPreferences();
            if (prefs.isEmpty()) return null;
            return "[用户偏好] " + prefs.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return null;
        }
    }

    // ======================== 对话管理 ========================

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments) {
        saveMessage(conversationId, role, content, fileAttachments, null, null, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments, String chartOption) {
        saveMessage(conversationId, role, content, fileAttachments, chartOption, null, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments, String chartOption, Integer tokenUsage) {
        saveMessage(conversationId, role, content, fileAttachments, chartOption, tokenUsage, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments, String chartOption, Integer tokenUsage, String conclusion) {
        ConversationMessage record = new ConversationMessage();
        record.setConversationId(conversationId);
        record.setRole(role);
        record.setContent(content);
        record.setFileAttachments(fileAttachments);
        record.setChartOption(chartOption);
        record.setTokenUsage(tokenUsage);
        record.setConclusion(conclusion);
        conversationMessageService.save(record);
        trimConversationHistory(conversationId);
    }

    private void trimConversationHistory(Long conversationId) {
        int maxMessages = MAX_CONVERSATION_ROUNDS * 2;
        long total = conversationMessageService.count(
                new QueryWrapper<ConversationMessage>().eq("conversationId", conversationId));
        if (total <= maxMessages) return;
        long excess = total - maxMessages;
        List<ConversationMessage> oldest = conversationMessageService.list(
                new QueryWrapper<ConversationMessage>()
                        .eq("conversationId", conversationId)
                        .orderByAsc("createTime")
                        .last("LIMIT " + excess));
        List<Long> ids = oldest.stream().map(ConversationMessage::getId).collect(Collectors.toList());
        conversationMessageService.removeByIds(ids);
        log.info("对话 {} 历史已修剪: 删除 {} 条旧消息，保留最近 {} 轮",
                conversationId, ids.size(), MAX_CONVERSATION_ROUNDS);
    }

    @Override
    public List<MessageVO> getConversationMessages(Long conversationId) {
        QueryWrapper<ConversationMessage> wrapper = new QueryWrapper<>();
        wrapper.eq("conversationId", conversationId).orderByAsc("createTime");
        return conversationMessageService.list(wrapper).stream().map(this::toMessageVO).collect(Collectors.toList());
    }

    @Override
    public List<MessageVO> getChartMessages(Long conversationId) {
        QueryWrapper<ConversationMessage> wrapper = new QueryWrapper<>();
        wrapper.eq("conversationId", conversationId)
                .isNotNull("chartOption")
                .orderByDesc("createTime");
        return conversationMessageService.list(wrapper).stream().map(this::toMessageVO).collect(Collectors.toList());
    }

    private MessageVO toMessageVO(ConversationMessage msg) {
        MessageVO vo = new MessageVO();
        vo.setId(msg.getId()); vo.setRole(msg.getRole());
        vo.setContent(msg.getContent()); vo.setCreateTime(msg.getCreateTime());
        vo.setFileAttachments(msg.getFileAttachments());
        vo.setChartOption(msg.getChartOption());
        vo.setTokenUsage(msg.getTokenUsage());
        vo.setConclusion(msg.getConclusion());
        return vo;
    }

    // ─── 流式输出辅助 ─────────────────────────────────────

    /**
     * 基于内容长度估算 token 数（chars/2）。
     * 中英文混合场景下约 2 字符/token，估算精度满足对话级预算判定。
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.0);
    }

    /**
     * 将字符串按固定长度拆分为多个 chunk，用于模拟流式输出。
     */
    private static List<String> splitIntoChunks(String text) {
        if (text == null || text.isEmpty()) return List.of();
        int chunkSize = IntentRuleMatcher.STREAM_CHUNK_SIZE;
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    // ─── 结论提取 ────────────────────────────────────────

    /**
     * 从 AI 回复中提取 ##CONCLUSION## ... ##END## 标记内的结论文本。
     *
     * @return 结论文本（无首尾空白），若未找到标记则返回 null
     */
    private static String extractConclusion(String content) {
        if (content == null) return null;
        Matcher matcher = CONCLUSION_PATTERN.matcher(content);
        if (matcher.find()) {
            String text = matcher.group(1).trim();
            // 清理结论中可能混入的 markdown 标题标记（## 标题 → 标题）
            text = text.replaceAll("^#{1,6}\\s+", "").trim();
            return text;
        }
        return null;
    }

    /**
     * 移除 ##CONCLUSION## ... ##END## 标记及内容，保留其余部分。
     * 如果找不到标记则原样返回。
     */
    private static String removeConclusionMarkers(String content) {
        if (content == null) return null;
        return content.replaceAll(
                "##CONCLUSION##\\r?\\n[\\s\\S]*?\\r?\\n##END##\\r?\\n?", "").trim();
    }

    // ─── 流式输出辅助 ─────────────────────────────────────

    /**
     * 统一表格格式：将 LLM 输出的压缩表格（同一行中用 {@code ||} 分隔多行）
     * 转换为标准换行分隔的多行表格。
     * <p>
     * 例如 {@code |a|b||c|d|} → {@code |a|b|\n|c|d|}
     * <br>
     * 注意：{@code ||} 可能在 LLM token 边界产生（token 1 尾 {@code |} + token 2 首 {@code |}），
     * 所以不能假设 {@code ||} 只出现在以 {@code |} 开头的行中。
     * {@code ||} 在自然语言中几乎不存在，可以安全全局替换。
     */
    private static String normalizeTableFormat(String text) {
        if (text == null || text.isEmpty()) return text;
        // 全局替换 || → |\n|，不限定行首，因为 || 可能在 token 拼接处产生
        return text.replaceAll("\\|{2,}", "|\n|");
    }
}
