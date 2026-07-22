package com.AIBI.controller;

import com.AIBI.common.BaseResponse;
import com.AIBI.common.ErrorCode;
import com.AIBI.common.ResultUtils;
import com.AIBI.config.DisconnectGraceManager;
import com.AIBI.config.SseEventBuffer;
import com.AIBI.config.SseEventBufferManager;
import com.AIBI.exception.BusinessException;
import com.AIBI.exception.ThrowUtils;
import com.AIBI.model.vo.MessageVO;
import com.AIBI.service.AgentService;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Agent SSE 接口。运行流以 runId 隔离，会话 ID 仅用于业务归属。 */
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

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(
            @RequestParam("message") String message,
            @RequestParam(value = "fileIds", required = false) String fileIds,
            @RequestParam(value = "fileScope", defaultValue = "legacy") String fileScope,
            @RequestParam(value = "renderProtocol", required = false) String protocolParam,
            @RequestHeader(value = "X-Agent-Render-Protocol", required = false) String protocolHeader) {
        ThrowUtils.throwIf(StringUtils.isBlank(message), ErrorCode.PARAMS_ERROR, "消息不能为空");

        String protocol = StringUtils.defaultIfBlank(protocolHeader, protocolParam);
        String runId = UUID.randomUUID().toString();
        String resumeToken = bufferManager.generateResumeToken(runId);
        SseEmitter emitter = newEmitter();
        SseEventBuffer buffer = bufferManager.getOrCreate(runId);
        buffer.setRunId(runId);
        Sinks.Many<Map<String, String>> sink = Sinks.many().replay().limit(512);
        graceManager.register(runId, sink, buffer, resumeToken);

        if (!graceManager.attachClient(runId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行已结束或已过期");
        }
        reactor.core.Disposable clientSubscription = forward(sink.asFlux(), emitter, runId);
        bindClientLifecycle(emitter, clientSubscription, runId);
        AtomicLong sequence = new AtomicLong();
        emit(sink, buffer, sequence, "meta", JSON.toJSONString(Map.of(
                "runId", runId,
                "renderProtocol", "render-document.v1".equals(protocol) ? "render-document.v1" : "legacy",
                "resumeToken", resumeToken,
                "replaySupported", true)));

        List<Long> ids = parseFileIds(fileIds);
        Flux<Map<String, String>> events = agentService
                .chatStream(message, ids, protocol, runId, "explicit".equals(fileScope))
                .publish()
                .refCount(1);
        Flux<Map<String, String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> Map.of("type", "ping", "data", ""))
                .takeUntilOther(events.ignoreElements());
        Map<String, String> complete = Map.of("type", "complete", "data", JSON.toJSONString(Map.of(
                "type", "complete", "runId", runId, "resumeToken", resumeToken)));

        reactor.core.Disposable sourceSubscription = Flux.concat(Flux.merge(events, heartbeat), Mono.just(complete))
                .map(event -> enrich(event, sequence, buffer))
                .doFinally(ignored -> buffer.markStreamEnd())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        sink::tryEmitNext,
                        error -> {
                            sink.tryEmitNext(enrich(errorEvent(error), sequence, buffer));
                            graceManager.completeRun(runId, DisconnectGraceManager.RunState.FAILED);
                            sink.tryEmitComplete();
                        },
                        () -> {
                            graceManager.completeRun(runId, DisconnectGraceManager.RunState.COMPLETED);
                            sink.tryEmitComplete();
                        });
        graceManager.setColdSubscription(runId, sourceSubscription);
        return emitter;
    }

    @PostMapping("/chat/resume")
    public SseEmitter resumeStream(
            @RequestParam("runId") String runId,
            @RequestParam("resumeToken") String resumeToken,
            @RequestParam(value = "lastEventId", defaultValue = "-1") long lastEventId) {
        if (!graceManager.validateResumeToken(runId, resumeToken)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "resumeToken 无效或已过期");
        }
        Sinks.Many<Map<String, String>> sink = graceManager.getSink(runId);
        if (sink == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该运行已结束或已过期");
        }
        if (!graceManager.attachClient(runId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该运行已结束或已过期");
        }

        SseEmitter emitter = newEmitter();
        reactor.core.Disposable subscription = forward(
                sink.asFlux().filter(event -> isAfter(event.get("seq"), lastEventId)), emitter, runId);
        bindClientLifecycle(emitter, subscription, runId);
        return emitter;
    }

    @GetMapping("/chat/runs/{runId}")
    public BaseResponse<Map<String, Object>> getRunStatus(
            @PathVariable String runId,
            @RequestParam("resumeToken") String resumeToken) {
        if (!graceManager.validateResumeToken(runId, resumeToken)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行不存在或恢复凭据已过期");
        }
        DisconnectGraceManager.RunSnapshot snapshot = graceManager.snapshot(runId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行状态已过期");
        }
        return ResultUtils.success(Map.of(
                "state", snapshot.state().name(),
                "resumable", snapshot.resumable()));
    }

    @GetMapping("/conversation")
    public BaseResponse<Long> getDefaultConversation() {
        return ResultUtils.success(agentService.getOrCreateDefaultConversation());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public BaseResponse<List<MessageVO>> getMessages(@PathVariable Long conversationId) {
        return ResultUtils.success(agentService.getConversationMessages(conversationId));
    }

    @GetMapping("/conversations/{conversationId}/chart-messages")
    public BaseResponse<List<MessageVO>> getChartMessages(@PathVariable Long conversationId) {
        return ResultUtils.success(agentService.getChartMessages(conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}/messages")
    public BaseResponse<Void> deleteMessages(@PathVariable Long conversationId) {
        agentService.deleteMessages(conversationId);
        return ResultUtils.success(null);
    }

    @PostMapping("/conversations/{conversationId}/reset")
    public BaseResponse<Void> resetConversation(@PathVariable Long conversationId) {
        agentService.resetConversation(conversationId);
        return ResultUtils.success(null);
    }

    private SseEmitter newEmitter() {
        return new SseEmitter((long) agentTimeoutSeconds * 1000 + 30_000L);
    }

    private List<Long> parseFileIds(String fileIds) {
        if (StringUtils.isBlank(fileIds)) return null;
        try {
            List<Long> result = new ArrayList<>();
            for (String value : fileIds.split(",")) {
                if (StringUtils.isNotBlank(value)) result.add(Long.parseLong(value.trim()));
            }
            return result;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件 ID 格式错误");
        }
    }

    private Map<String, String> enrich(Map<String, String> event, AtomicLong sequence, SseEventBuffer buffer) {
        long id = sequence.getAndIncrement();
        Map<String, String> enriched = new LinkedHashMap<>(event);
        enriched.put("seq", String.valueOf(id));
        if (!"ping".equals(event.get("type"))) buffer.append(id, event.get("type"), event.get("data"));
        return enriched;
    }

    private void emit(Sinks.Many<Map<String, String>> sink, SseEventBuffer buffer,
                      AtomicLong sequence, String type, String data) {
        sink.tryEmitNext(enrich(Map.of("type", type, "data", data), sequence, buffer));
    }

    private Map<String, String> errorEvent(Throwable error) {
        String message = StringUtils.defaultIfBlank(error.getMessage(), "流式处理失败");
        return Map.of("type", "error", "data", JSON.toJSONString(Map.of("message", message)));
    }

    private boolean isAfter(String eventId, long lastEventId) {
        if (eventId == null) return false;
        try {
            return Long.parseLong(eventId) > lastEventId;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private reactor.core.Disposable forward(Flux<Map<String, String>> events, SseEmitter emitter, String runId) {
        return events.subscribeOn(Schedulers.boundedElastic()).subscribe(event -> {
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event()
                        .name(event.get("type"))
                        .data(event.get("data"));
                if (event.get("seq") != null) builder.id(event.get("seq"));
                emitter.send(builder);
            } catch (IOException e) {
                throw new ClientDisconnectedException(e);
            }
        }, error -> {
            if (isClientDisconnected(error)) {
                log.debug("SSE客户端已断开: runId={}", runId);
                emitter.complete();
                return;
            }
            log.warn("SSE客户端发送失败: runId={}", runId, error);
            emitter.completeWithError(error);
        }, emitter::complete);
    }

    private void bindClientLifecycle(SseEmitter emitter, reactor.core.Disposable subscription, String runId) {
        java.util.concurrent.atomic.AtomicBoolean detached = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable detach = () -> {
            if (detached.compareAndSet(false, true)) {
                subscription.dispose();
                graceManager.detachClient(runId);
            }
        };
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
    }

    private boolean isClientDisconnected(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ClientDisconnectedException || current instanceof IOException) return true;
            String name = current.getClass().getSimpleName();
            if ("ClientAbortException".equals(name) || "AsyncRequestNotUsableException".equals(name)) return true;
            String message = current.getMessage();
            if (message != null && message.contains("Response not usable after response errors")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static final class ClientDisconnectedException extends RuntimeException {
        private ClientDisconnectedException(IOException cause) {
            super(cause);
        }
    }
}
