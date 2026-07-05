package com.AIBI.controller;

import com.AIBI.common.BaseResponse;
import com.AIBI.common.ErrorCode;
import com.AIBI.common.ResultUtils;
import com.AIBI.exception.BusinessException;
import com.AIBI.exception.ThrowUtils;
import com.AIBI.config.DisconnectGraceManager;
import com.AIBI.config.SseEventBuffer;
import com.AIBI.config.SseEventBufferManager;
import com.AIBI.model.vo.MessageVO;
import com.AIBI.service.AgentService;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 对话接口（单对话模式）。
 */
@RestController
@RequestMapping("/apis/agent")
@Slf4j
public class AgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private SseEventBufferManager bufferManager;

    @Autowired
    private DisconnectGraceManager graceManager;

    @Value("${agent.global-timeout-seconds:300}")
    private int agentTimeoutSeconds;

    /**
     * Agent 对话（流式 SSE）。
     *
     * @param fileIds 可选，本次消息绑定的数据文件 ID，逗号分隔。传此参数后 agent 仅查询指定文件。
     */
    @PostMapping(value = "/chat/stream")
    public SseEmitter chatStream(
            @RequestParam("message") String message,
            @RequestParam(value = "fileIds", required = false) String fileIds) {
        ThrowUtils.throwIf(StringUtils.isBlank(message), ErrorCode.PARAMS_ERROR, "消息不能为空");

        List<Long> fileIdList = null;
        if (StringUtils.isNotBlank(fileIds)) {
            try {
                fileIdList = new ArrayList<>();
                for (String part : fileIds.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        fileIdList.add(Long.parseLong(trimmed));
                    }
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件 ID 格式错误");
            }
        }

        // 对齐前端超时(300s) + 30s 优雅余量
        SseEmitter emitter = new SseEmitter((long) agentTimeoutSeconds * 1000 + 30_000L);

        Long cid = agentService.getOrCreateDefaultConversation();
        String cidStr = cid.toString();

        // 初始化事件缓冲区 + 热点 Sinks.Many + resumeToken
        SseEventBuffer buffer = bufferManager.getOrCreate(cidStr);
        Sinks.Many<Map<String, String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        graceManager.register(cidStr, sink, buffer);
        String resumeToken = bufferManager.generateResumeToken(cidStr);

        // 冷事件流 + 心跳 + 序号
        var eventFlux = agentService.chatStream(message, fileIdList);

        // publish + connect: 将冷 Flux 转为热点，后续所有 subscriber 共享同一次执行
        ConnectableFlux<Map<String, String>> hotFlux = eventFlux.publish();
        reactor.core.Disposable hotConn = hotFlux.connect();

        Flux<Map<String, String>> hbFlux = Flux.interval(Duration.ofSeconds(15))
                .map(i -> Map.of("type", "ping", "data", ""))
                .takeUntilOther(hotFlux.then(Mono.empty()));

        AtomicLong seqCounter = new AtomicLong(0);

        // 先用一个 status 事件把 resumeToken 发给前端（不等 complete 事件）
        Map<String, String> tokenEvent = new LinkedHashMap<>();
        tokenEvent.put("type", "status");
        tokenEvent.put("data", "resumeToken:" + resumeToken);
        sink.tryEmitNext(tokenEvent);

        reactor.core.Disposable coldSub = Flux.merge(hotFlux, hbFlux)
            .map(event -> {
                long seq = seqCounter.getAndIncrement();
                if (!"ping".equals(event.get("type"))) {
                    buffer.append(seq, event.get("type"), event.get("data"));
                }
                Map<String, String> enriched = new LinkedHashMap<>(event);
                enriched.put("seq", String.valueOf(seq));
                return enriched;
            })
            .doFinally(signal -> {
                buffer.markStreamEnd();
                hotConn.dispose();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                enriched -> sink.tryEmitNext(enriched),
                error -> {
                    log.error("冷 Flux 异常, cid={}", cid, error);
                    sink.tryEmitError(error);
                },
                () -> sink.tryEmitComplete()
            );
        graceManager.setColdSubscription(cidStr, coldSub);

        // 首次订阅热点 → SseEmitter
        reactor.core.Disposable disposable = sink.asFlux()
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.get("type"))
                                .data(event.get("data")));
                    } catch (IOException e) {
                        throw new RuntimeException("SSE 发送失败，客户端可能已断开", e);
                    }
                },
                error -> {
                    log.error("SSE 流式对话失败, cid={}", cid);
                    try {
                        Map<String, String> errResult = new LinkedHashMap<>();
                        errResult.put("type", "error");
                        errResult.put("message", error.getMessage());
                        emitter.send(SseEmitter.event().name("complete").data(JSON.toJSONString(errResult)));
                    } catch (Exception ignored) {}
                    emitter.completeWithError(error);
                },
                () -> {
                    try {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("type", "complete");
                        result.put("chartOption", agentService.getLastChartOption());
                        result.put("conversationId", cid);
                        result.put("tokenUsage", agentService.getLastTokenUsage());
                        result.put("resumeToken", resumeToken);
                        emitter.send(SseEmitter.event().name("complete").data(JSON.toJSONString(result)));
                    } catch (Exception e) {
                        log.error("发送 complete 事件失败", e);
                    }
                    emitter.complete();
                }
            );

        // 注册 SseEmitter 回调
        emitter.onCompletion(() -> {
            log.debug("SSE 连接正常关闭: cid={}", cid);
            disposable.dispose();
        });

        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时, 启动优雅窗格: cid={}", cidStr);
            disposable.dispose();
            graceManager.onDisconnect(cidStr);
        });

        return emitter;
    }

    /**
     * 重连 SSE 流 — 用于断线后的续播恢复。
     * <p>
     * 客户端断连后通过此端点重新订阅热点 Sinks.Many：
     * 1. 验证 resumeToken 防止恶意重连
     * 2. 从 SseEventBuffer 回放 resumeSeq 之后的事件
     * 3. 接回实时热点事件流
     * <p>
     * 不获取 Redisson 锁（原流已持有），直接流入 GraceManager 管理的 Sinks.Many。
     */
    @PostMapping(value = "/chat/resume")
    public SseEmitter resumeStream(
            @RequestParam("conversationId") Long conversationId,
            @RequestParam("resumeToken") String resumeToken,
            @RequestParam(value = "resumeSeq", defaultValue = "-1") long resumeSeq) {
        String cidStr = conversationId.toString();

        // 1. 验证 resumeToken
        if (!bufferManager.validateResumeToken(cidStr, resumeToken)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "resumeToken 无效或已过期");
        }

        // 2. 获取 GraceManager 中活跃的 Sinks.Many
        Sinks.Many<Map<String, String>> sink = graceManager.getSink(cidStr);
        if (sink == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该对话的流已结束或已过期");
        }

        // 3. 通过 GraceManager 确认重连
        graceManager.onReconnect(cidStr);

        // 4. 创建 SseEmitter
        SseEmitter emitter = new SseEmitter((long) agentTimeoutSeconds * 1000 + 30_000L);

        // 5. 先从 EventBuffer 回放历史事件（从 resumeSeq 开始）
        SseEventBuffer buffer = bufferManager.getIfPresent(cidStr);
        if (buffer != null && resumeSeq >= 0) {
            var replayEvents = buffer.getFromSeq(resumeSeq);
            for (var event : replayEvents) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.type())
                            .data(event.data()));
                } catch (IOException e) {
                    log.warn("重连回放发送失败, cid={}, seq={}", cidStr, event.seq());
                    emitter.completeWithError(e);
                    return emitter;
                }
            }
            log.info("回放了 {} 个历史事件，接回实时流: cid={}, fromSeq={}",
                    replayEvents.size(), cidStr, resumeSeq);
        }

        // 6. 订阅热点 Sinks.Many → SseEmitter（接回实时流）
        reactor.core.Disposable disposable = sink.asFlux()
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.get("type"))
                                .data(event.get("data")));
                    } catch (IOException e) {
                        throw new RuntimeException("SSE 重连发送失败", e);
                    }
                },
                error -> {
                    log.error("SSE 重连流异常, cid={}", cidStr);
                    emitter.completeWithError(error);
                },
                () -> emitter.complete()
            );

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            graceManager.onDisconnect(cidStr);
        });

        return emitter;
    }

    /** 获取默认对话 ID（前端据此查询历史消息） */
    @GetMapping("/conversation")
    public BaseResponse<Long> getDefaultConversation() {
        return ResultUtils.success(agentService.getOrCreateDefaultConversation());
    }

    /** 获取对话的消息历史 */
    @GetMapping("/conversations/{conversationId}/messages")
    public BaseResponse<List<MessageVO>> getMessages(@PathVariable Long conversationId) {
        return ResultUtils.success(agentService.getConversationMessages(conversationId));
    }

    /** 获取对话中含图表的消息列表（按创建时间倒序） */
    @GetMapping("/conversations/{conversationId}/chart-messages")
    public BaseResponse<List<MessageVO>> getChartMessages(@PathVariable Long conversationId) {
        return ResultUtils.success(agentService.getChartMessages(conversationId));
    }

    /** 清空指定对话的所有消息。仅清除数据库中的消息记录，不影响 Agent 记忆。 */
    @DeleteMapping("/conversations/{conversationId}/messages")
    public BaseResponse<Void> deleteMessages(@PathVariable Long conversationId) {
        agentService.deleteMessages(conversationId);
        return ResultUtils.success(null);
    }

    /**
     * 重置指定对话 — 清除所有消息、Redis Agent 记忆、分析状态和服务缓存。
     * 对话本身保留，绑定的数据文件不受影响。
     */
    @PostMapping("/conversations/{conversationId}/reset")
    public BaseResponse<Void> resetConversation(@PathVariable Long conversationId) {
        agentService.resetConversation(conversationId);
        return ResultUtils.success(null);
    }
}
