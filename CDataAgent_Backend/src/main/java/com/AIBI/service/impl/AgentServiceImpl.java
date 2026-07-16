package com.AIBI.service.impl;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.model.PresentationPlan;
import com.AIBI.agent.model.RenderDocument;
import com.AIBI.agent.model.RenderDocumentAssembler;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.agent.run.RunActivity;
import com.AIBI.agent.run.AgentLockKeys;
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
import com.alibaba.fastjson2.JSON;
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
import java.util.UUID;
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

    @Autowired
    private RenderDocumentAssembler renderDocumentAssembler;

    @Value("${agent.global-timeout-seconds:300}")
    private int agentTimeoutSeconds;

    @Value("${agent.context.inject-analysis-state:true}")
    private boolean injectAnalysisState;

    private static final String LOCK_PREFIX = "agent:lock:";
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final int MAX_CONVERSATION_ROUNDS = 50;

    /** 结论标记正则：##CONCLUSION##\n...\n##END##（兼容 CRLF \r\n） */
    private static final Pattern CONCLUSION_PATTERN = Pattern.compile(
            "##CONCLUSION##\\r?\\n([\\s\\S]*?)\\r?\\n##END##", Pattern.MULTILINE);

    /** 默认对话 ID 缓存（首次访问时初始化） */
    private volatile Long defaultConversationId;


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
        log.info("默认对话已创建");
        return defaultConversationId;
    }

    @Override
    public void deleteMessages(Long conversationId) {
        ThrowUtils.throwIf(conversationId == null, ErrorCode.PARAMS_ERROR, "对话ID不能为空");
        Conversation conv = conversationService.getById(conversationId);
        ThrowUtils.throwIf(conv == null, ErrorCode.NOT_FOUND_ERROR, "对话不存在");

        conversationMessageService.remove(
                new QueryWrapper<ConversationMessage>().eq("conversationId", conversationId));
        log.info("聊天记录已清空");
    }

    @Override
    public void resetConversation(Long conversationId) {
        // ── 参数校验 ──
        ThrowUtils.throwIf(conversationId == null, ErrorCode.PARAMS_ERROR, "对话ID不能为空");
        Conversation conv = conversationService.getById(conversationId);
        ThrowUtils.throwIf(conv == null, ErrorCode.NOT_FOUND_ERROR, "对话不存在");

        // ── 获取全局运行锁和对话锁，防止与活跃流冲突 ──
        RLock globalLock = redissonClient.getLock(AgentLockKeys.GLOBAL_RUN_LOCK);
        RLock lock = redissonClient.getLock(LOCK_PREFIX + conversationId);
        boolean globalLocked = false;
        boolean conversationLocked = false;
        try {
            globalLocked = globalLock.tryLock(0, TimeUnit.SECONDS);
            if (!globalLocked) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                        "任务正在进行中，无法重置");
            }
            // 0-wait: 锁被持有说明有活跃对话，直接拒绝
            conversationLocked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!conversationLocked) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                        "对话正在进行中，无法重置");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (globalLocked) {
                unlockQuietly(globalLock);
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙");
        } catch (RuntimeException e) {
            if (conversationLocked) {
                unlockQuietly(lock);
            }
            if (globalLocked) {
                unlockQuietly(globalLock);
            }
            throw e;
        }

        try {
            // Step 1: 删除 H2 消息
            conversationMessageService.remove(
                    new QueryWrapper<ConversationMessage>().eq("conversationId", conversationId));
            log.debug("1/6：消息已删除");

            // Step 2: 清空 AnalysisState
            analysisState.resetByConversation(conversationId.toString());
            log.debug("2/6：分析状态已清空");

            // Step 3: 重置 TokenLedger
            tokenLedger.reset(conversationId);
            log.debug("3/6：Token用量已重置");

            // Step 4: 删除 Redis checkpoint key
            ttlRedisSaver.deleteCheckpoints("executor:" + conversationId);
            // 清理改造前 Executor/Synthesizer 共用的历史 Checkpoint。
            ttlRedisSaver.deleteCheckpoints(conversationId.toString());
            log.debug("4/6：Redis检查点已删除");

            // Step 5: 清空 defaultConversationId 缓存
            if (Long.valueOf(conversationId).equals(defaultConversationId)) {
                defaultConversationId = null;
                log.debug("5/6：对话ID缓存已清空");
            }

            log.info("对话已完全重置: title={}", conv.getTitle());
        } catch (Exception e) {
            log.error("重置对话异常", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重置对话失败: " + e.getMessage());
        } finally {
            // 释放锁
            unlockQuietly(lock);
            unlockQuietly(globalLock);
        }
    }

    // ======================== 核心编排 ========================

    @Override
    public Flux<Map<String, String>> chatStream(String userMessage) {
        return chatStream(userMessage, null, null);
    }

    @Override
    public Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds) {
        return chatStream(userMessage, fileIds, null);
    }

    @Override
    public Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds, String renderProtocol) {
        return chatStream(userMessage, fileIds, renderProtocol, null);
    }

    @Override
    public Flux<Map<String, String>> chatStream(String userMessage, List<Long> fileIds,
                                                  String renderProtocol, String runId) {
        ThrowUtils.throwIf(StringUtils.isBlank(userMessage), ErrorCode.PARAMS_ERROR, "消息不能为空");

        return Flux.defer(() -> {
            // Layer 1: 规则层 — 零模型开销拦截问候/告别/文件查询等
            Optional<IntentRuleMatcher.IntentRuleResult> ruleOpt = intentRuleMatcher.match(userMessage);
            if (ruleOpt.isPresent()) {
                IntentRuleMatcher.IntentRuleResult ruleResult = ruleOpt.get();
                log.debug("规则匹配命中，输入长度={}", userMessage.length());
                return Flux.fromIterable(splitIntoChunks(ruleResult.reply()))
                        .delayElements(Duration.ofMillis(IntentRuleMatcher.STREAM_CHUNK_DELAY_MS))
                        .map(chunk -> Map.of("type", "message", "data", normalizeTableFormat(chunk)));
            }

            Long cid = getOrCreateDefaultConversation();
            String effectiveRunId = StringUtils.defaultIfBlank(runId, UUID.randomUUID().toString());

            RLock globalLock = redissonClient.getLock(AgentLockKeys.GLOBAL_RUN_LOCK);
            RLock lock = redissonClient.getLock(LOCK_PREFIX + cid);
            try {
                if (!globalLock.tryLock(0, TimeUnit.SECONDS)) {
                    return Flux.error(new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                            "已有任务正在运行，请等待完成后再发送"));
                }
                if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    unlockQuietly(globalLock);
                    return Flux.error(new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                            "对话处理中，请稍后再试"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unlockQuietly(globalLock);
                return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙"));
            }

            // 创建 RunContext（请求级运行上下文）
            RunContext runContext = new RunContext(effectiveRunId, cid);
            runContext.setRenderProtocol(renderProtocol);
            boolean analysisStateInitialized = false;
            try {
                RunContextHolder.set(runContext);

                // 设置本轮 active 文件（前端传递的文件绑定）
                analysisState.setCurrentThreadId(cid.toString());
                analysisStateInitialized = true;
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

                log.info("运行开始 文件数={}", fileNames.size());

                modelManager.setCurrentConversationId(cid);
                exactTokenCounter.setCurrentConversationId(cid);
                tokenLedger.initRound(cid);

                return executePipeline(cid, effectiveMessage, userMessage, lock, globalLock, fileAttachments, runContext);
            } catch (RuntimeException e) {
                runContext.clear();
                RunContextHolder.clear(runContext);
                if (analysisStateInitialized) {
                    analysisState.clearByConversation(cid.toString());
                }
                duckDbConnectionManager.close(cid.toString());
                tokenLedger.discardRound(cid);
                modelManager.clearCurrentConversationId();
                exactTokenCounter.clear();
                unlockQuietly(lock);
                unlockQuietly(globalLock);
                return Flux.error(e);
            }
        });
    }

    private Flux<Map<String, String>> executePipeline(Long cid, String effectiveMessage,
                                                        String originalMessage, RLock lock, RLock globalLock,
                                                        String fileAttachments, RunContext runContext) {
        RunnableConfig config = RunnableConfig.builder().threadId("executor:" + cid).build();
        Duration totalTimeout = Duration.ofSeconds(agentTimeoutSeconds);
        StringBuilder fullResponse = new StringBuilder();
        runContext.beginRequirementUnderstanding();
        runContext.setModelStage("executor");
        log.info("阶段=executor");

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

        boolean isV1 = "render-document.v1".equals(runContext.getRenderProtocol());

        Flux<Map<String, String>> executorEvents = executorFlux
                .take(totalTimeout)
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> runContext.completeRequirementUnderstanding(RunActivity.State.SUCCEEDED))
                .onErrorResume(Exception.class, e -> {
                    runContext.completeRequirementUnderstanding(RunActivity.State.FAILED);
                    log.error("运行失败 runId={}", runContext.getRunId(), e);
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                        var wce = (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                        log.warn("模型错误：状态码={}、响应体长度={}",
                                wce.getStatusCode(), wce.getResponseBodyAsString().length());
                    }
                    String errMsg = "【系统】处理失败: " + e.getMessage();
                    fullResponse.append(errMsg);
                    return Flux.just(errMsg);
                })
                .flatMap(token -> {
                    // 分析型请求只交付受控产物；普通对话保留实时 Markdown token。
                    if (isV1 && runContext.isAnalysisIntent()) return Flux.empty();
                    String clean = token
                            .replaceAll("#+NEEDS_CHART#*", "")
                            .replace("【【【【【", "")
                            .replace("】】】】】", "")
                            .trim();
                    if (clean.isEmpty()) return Flux.empty();
                    return Flux.just(Map.of("type", "message", "data", normalizeTableFormat(clean)));
                });

        Flux<Map<String, String>> planSnapshot = isV1
                ? reactor.core.publisher.Mono.fromFuture(runContext.getPresentationPlanReadyFuture())
                .filter(java.util.Objects::nonNull)
                .filter(plan -> StringUtils.isNotBlank(plan.getSummary())
                        || (plan.getBulletItems() != null && !plan.getBulletItems().isEmpty())
                        || plan.hasTables())
                .flatMapMany(plan -> {
                    int tableCount = plan.getTableOutputKeys() == null ? 0 : plan.getTableOutputKeys().size();
                    int chartCount = plan.getChartOutputKeys() == null ? 0 : plan.getChartOutputKeys().size();
                    log.info("阶段=plan_submitted 表格={} 图表={}", tableCount, chartCount);
                    RenderDocument doc = renderDocumentAssembler.assembleWithoutCharts(plan, runContext.getRunId());
                    return artifactEvents(doc, plan.hasCharts());
                })
                : Flux.empty();

        Flux<Map<String, String>> initialEvents = executorEvents.publish(shared ->
                Flux.merge(shared, planSnapshot.takeUntilOther(shared.ignoreElements())));

        Flux<Map<String, String>> activityEvents = runContext.activityEvents().map(AgentServiceImpl::activityEvent);

        return Flux.merge(
                Flux.concat(initialEvents)
        .concatWith(Flux.defer(() -> {
                    String response = fullResponse.toString().trim();
                    RunContext ctx = runContext;
                    PresentationPlan plan = ctx != null ? ctx.getPresentationPlan() : null;
                    int tableCount = plan != null && plan.getTableOutputKeys() != null
                            ? plan.getTableOutputKeys().size() : 0;
                    int chartCount = plan != null && plan.getChartOutputKeys() != null
                            ? plan.getChartOutputKeys().size() : 0;
                    log.info("阶段=plan_ready 表格={} 图表={}", tableCount, chartCount);

                    // 判断是否需要触发 Synthesizer
                    boolean needsChart;
                    String synthPrompt;
                    if (isV1) {
                        // v1 协议：从 PresentationPlan.chartOutputKeys 判断
                        needsChart = plan != null && plan.hasCharts();
                        if (needsChart) {
                            log.debug("图表生成触发 runId={} 图表数={}", runContext.getRunId(), plan.getChartOutputKeys().size());
                            // 用 plan 的摘要和图表 key 构造 Synthesizer 提示词
                            StringBuilder sb = new StringBuilder();
                            sb.append("用户请求: ").append(originalMessage).append("\n\n");
                            if (plan.getSummary() != null && !plan.getSummary().isBlank()) {
                                sb.append("分析摘要: ").append(plan.getSummary()).append("\n\n");
                            }
                            sb.append("图表输出键: ").append(String.join(", ", plan.getChartOutputKeys()));
                            sb.append("\n\n请基于以上信息生成图表配置。");
                            synthPrompt = sb.toString();
                            // 重置图表就绪信号（确保信号来自本轮 Synthesizer）
                            if (ctx != null) ctx.resetChartReadyFuture();
                        } else {
                            synthPrompt = null;
                        }
                    } else {
                        // 旧协议：检查 ##NEEDS_CHART## 标记
                        needsChart = response.contains("##NEEDS_CHART##");
                        if (needsChart) {
                            log.debug("图表生成触发（旧协议）");
                            String cleanText = response.replace("##NEEDS_CHART##", "").trim();
                            synthPrompt = "用户请求: " + originalMessage + "\n\n分析结果:\n" + cleanText +
                                    "\n\n请基于以上分析结果生成图表配置。";
                        } else {
                            synthPrompt = null;
                        }
                    }

                    if (!needsChart) return isV1 ? Flux.empty() : previewFlux(ctx, response);
                    log.info("阶段=synthesizer 图表={}", chartCount);

                    // 图表仅在生成与校验完成后随最终持久化结果开放，避免半成品闪现。
                    return synthesizeCharts(synthPrompt, config, runContext);
                }))
                // 最终文档只用于持久化和历史恢复；流式过程不替换已有 UI。
                .concatWith(Flux.defer(() -> {
                    if (!isV1) return Flux.empty();
                    RunContext ctx = runContext;
                    String ctxRunId = ctx != null ? ctx.getRunId() : null;
                    PresentationPlan plan = ctx != null ? ctx.getPresentationPlan() : null;
                    RenderDocument doc = plan == null
                            ? renderDocumentAssembler.assembleTextResponse(fullResponse.toString(), ctxRunId)
                            : renderDocumentAssembler.assemble(plan, ctxRunId);
                    if (ctx != null) ctx.setLastRenderDocument(doc);
                    return Flux.empty();
                }))
                .concatWith(Flux.defer(() -> {
                    Finalization finalization = finalizeRun(cid, fileAttachments, fullResponse, runContext);
                    return Flux.just(chartResultEvent(finalization));
                }))
                .doFinally(signal -> {
                    String cidStr = cid.toString();
                    // 清除 RunContext（图表和意图字段）
                    RunContext ctx = runContext;
                    if (ctx != null) {
                        ctx.clear();
                    }
                    RunContextHolder.clear(runContext);
                    // 清除 AnalysisState（文件/步骤/dataIndex ConcurrentHashMap）
                    analysisState.clearByConversation(cidStr);
                    duckDbConnectionManager.close(cidStr);
                    tokenLedger.discardRound(cid);
                    modelManager.clearCurrentConversationId();
                    exactTokenCounter.clear();
                    runContext.completeActivityEvents();
                    unlockQuietly(lock);
                    unlockQuietly(globalLock);
                }),
                activityEvents
        );
    }

    private static void unlockQuietly(RLock lock) {
        try {
            if (lock != null && lock.isLocked()) {
                lock.forceUnlock();
            }
        } catch (Exception ignored) {
        }
    }

    private Flux<String> synthesizePhase(String prompt, RunnableConfig config) {
        log.debug("图表合成器开始运行");
        return Flux.defer(() -> {
            RunContext context = RunContextHolder.get();
            if (context != null) {
                context.setModelStage("synthesizer");
            }
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

    private Integer consumeRoundTokenUsage(Long cid) {
        return tokenLedger.consumeRoundUsage(cid)
                .map(TokenLedger.RoundTokenUsage::total)
                .map(Math::toIntExact)
                .orElse(null);
    }

    private static Map<String, String> progressEvent(String stage, String label, String state) {
        JSONObject payload = new JSONObject();
        payload.put("stage", stage);
        payload.put("label", label);
        payload.put("state", state);
        return Map.of("type", "progress", "data", payload.toJSONString());
    }

    /**
     * 文档事件与持久化文档分离：阶段和版本只服务于传输期，避免污染最终快照。
     */
    private static Map<String, String> documentEvent(RenderDocument document, String phase, int revision) {
        JSONObject payload = JSONObject.parseObject(document.toTransportJson());
        payload.put("phase", phase);
        payload.put("revision", revision);
        return Map.of("type", "document", "data", payload.toJSONString());
    }

    private static Map<String, String> artifactEvent(RenderDocument document, boolean chartExpected) {
        JSONObject payload = JSONObject.parseObject(document.toTransportJson());
        payload.put("chartExpected", chartExpected);
        return Map.of("type", "artifact", "data", payload.toJSONString());
    }

    /** 计划要求图表但首轮没有已校验产物时，仅补偿重试一次。 */
    private Flux<Map<String, String>> synthesizeCharts(String prompt, RunnableConfig config, RunContext context) {
        return synthesizePhase(prompt, config)
                .thenMany(Flux.defer(() -> {
                    if (context.hasChartOptions()) return Flux.<Map<String, String>>empty();
                    log.warn("阶段=synthesizer_retry 原因=无有效图表");
                    String retryPrompt = prompt + "\n\n上一轮未生成有效图表。现在必须依次调用 describeData、buildChart、validateChart，"
                            + "并使用上述图表输出键；不要只返回说明文本。";
                    return synthesizePhase(retryPrompt, config).thenMany(Flux.<Map<String, String>>empty());
                }))
                .doOnComplete(() -> log.info("阶段=synthesizer_finished 有图表={}", context.hasChartOptions()));
    }

    /** 完成持久化后再推送图表终态，避免客户端依赖异步刷新补齐关键状态。 */
    private Finalization finalizeRun(Long cid, String fileAttachments, StringBuilder fullResponse,
                                    RunContext context) {
        String runId = context != null ? context.getRunId() : null;
        PresentationPlan plan = context != null ? context.getPresentationPlan() : null;
        int plannedChartCount = plan != null && plan.getChartOutputKeys() != null
                ? plan.getChartOutputKeys().size() : 0;

        String content;
        String conclusion = null;
        String chartOption;
        String renderDocumentJson = null;
        Integer renderVersion = null;

        RenderDocument doc = context != null ? context.getLastRenderDocument() : null;
        if (doc == null && plan != null) {
            doc = renderDocumentAssembler.assemble(plan, runId);
        }

        if (doc != null) {
            renderDocumentJson = doc.toJson();
            renderVersion = doc.version();
            content = doc.toContentProjection();
            conclusion = doc.extractConclusion();
            chartOption = context != null ? context.consumeChartOptions() : null;
        } else {
            String raw = fullResponse.toString().trim()
                    .replaceAll("#+NEEDS_CHART#*", "")
                    .replace("【【【【【", "")
                    .replace("】】】】】", "")
                    .trim();
            conclusion = extractConclusion(raw);
            content = normalizeTableFormat(conclusion != null ? removeConclusionMarkers(raw) : raw);
            chartOption = context != null ? context.consumeChartOptions() : null;
        }

        if (content != null && !content.isEmpty()) {
            Integer tokenUsage = consumeRoundTokenUsage(cid);
            saveMessage(cid, "assistant", content, fileAttachments, chartOption,
                    tokenUsage, conclusion, renderDocumentJson, renderVersion);
            log.info("阶段=persisted 区块数={} 有图表={} 降级={}",
                    doc != null ? doc.blocks().size() : 0,
                    chartOption != null, doc != null && doc.degraded());
            if (tokenUsage != null) {
                log.info("本轮模型用量：{} Token", tokenUsage);
            }
        }
        if (plannedChartCount > 0 && chartOption == null) {
            log.warn("图表未生成：计划数={}", plannedChartCount);
        }
        if (context != null) {
            RunContext.QueryReplayMetrics replayMetrics = context.getQueryReplayMetrics();
            if (replayMetrics.count() > 0) {
                log.info("大结果重算：次数={}、耗时={}ms", replayMetrics.count(), replayMetrics.elapsedMs());
            }
        }
        return new Finalization(runId, plannedChartCount, chartOption);
    }

    private static Map<String, String> chartResultEvent(Finalization finalization) {
        int validatedChartCount = 0;
        if (StringUtils.isNotBlank(finalization.chartOption())) {
            try {
                validatedChartCount = JSON.parseArray(finalization.chartOption()).size();
            } catch (Exception ignored) {
                // 持久化已使用同一来源；事件退化为不可用状态，避免向客户端传递坏数据。
            }
        }
        String state = finalization.plannedChartCount() == 0 ? "not_requested"
                : validatedChartCount > 0 ? "ready" : "unavailable";
        JSONObject payload = new JSONObject();
        payload.put("runId", finalization.runId());
        payload.put("plannedChartCount", finalization.plannedChartCount());
        payload.put("validatedChartCount", validatedChartCount);
        payload.put("state", state);
        if ("ready".equals(state)) payload.put("chartOption", finalization.chartOption());
        return Map.of("type", "chart-result", "data", payload.toJSONString());
    }

    private record Finalization(String runId, int plannedChartCount, String chartOption) {}

    private static Flux<Map<String, String>> artifactEvents(RenderDocument document, boolean chartExpected) {
        return Flux.fromIterable(document.splitForStreaming())
                .map(item -> artifactEvent(item, chartExpected))
                // 按语义区块依次交付，避免摘要、要点和表格在同一帧同时进入客户端。
                .delayElements(Duration.ofMillis(120));
    }

    private static Map<String, String> activityEvent(RunActivity activity) {
        JSONObject payload = new JSONObject();
        payload.put("id", activity.id());
        payload.put("stage", activity.stage());
        payload.put("toolKey", activity.toolKey());
        payload.put("label", activity.label());
        payload.put("state", activity.state().name().toLowerCase());
        return Map.of("type", "activity", "data", payload.toJSONString());
    }

    private Flux<Map<String, String>> previewFlux(RunContext context, String fallbackText) {
        String summary = context != null && context.getPresentationPlan() != null
                ? context.getPresentationPlan().getSummary()
                : fallbackText;
        if (StringUtils.isBlank(summary)) return Flux.empty();
        return Flux.fromIterable(splitIntoChunks(summary))
                .delayElements(Duration.ofMillis(16))
                .map(chunk -> Map.of("type", "preview", "data", chunk));
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
                    log.debug("已恢复上一轮分析状态");
                }
            } else {
                log.debug("本轮绑定新文件({}个)，跳过状态恢复",
                        fileIds.size());
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
            log.warn("文件上下文查询失败", e);
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
        saveMessage(conversationId, role, content, fileAttachments, null, null, null, null, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments, String chartOption) {
        saveMessage(conversationId, role, content, fileAttachments, chartOption, null, null, null, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments, String chartOption, Integer tokenUsage) {
        saveMessage(conversationId, role, content, fileAttachments, chartOption, tokenUsage, null, null, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments, String chartOption, Integer tokenUsage, String conclusion) {
        saveMessage(conversationId, role, content, fileAttachments, chartOption, tokenUsage, conclusion, null, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String fileAttachments,
                             String chartOption, Integer tokenUsage, String conclusion,
                             String renderDocument, Integer renderVersion) {
        ConversationMessage record = new ConversationMessage();
        record.setConversationId(conversationId);
        record.setRole(role);
        record.setContent(content);
        record.setFileAttachments(fileAttachments);
        record.setChartOption(chartOption);
        record.setTokenUsage(tokenUsage);
        record.setConclusion(conclusion);
        record.setRenderDocument(renderDocument);
        record.setRenderVersion(renderVersion);
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
        log.info("历史已修剪：删除{}条旧消息，保留最近{}轮",
                ids.size(), MAX_CONVERSATION_ROUNDS);
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
        vo.setRenderDocument(msg.getRenderDocument());
        vo.setRenderVersion(msg.getRenderVersion());
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

    // ── content 确定性投影（RenderDocument → 纯文本，供旧客户端兼容） ──

    /**
     * 从 RenderDocument 生成确定性纯文本投影。
     * 不是 Markdown — 旧客户端通过兼容渲染器显示；新客户端使用 renderDocument 本身。
     */
    private static String projectContent(RenderDocument doc) {
        return doc.toContentProjection();
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
