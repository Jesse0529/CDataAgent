package com.AIBI.controller;

import com.AIBI.common.BaseResponse;
import com.AIBI.common.ErrorCode;
import com.AIBI.common.ResultUtils;
import com.AIBI.exception.BusinessException;
import com.AIBI.exception.ThrowUtils;
import com.AIBI.model.vo.MessageVO;
import com.AIBI.service.AgentService;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话接口（单对话模式）。
 */
@RestController
@RequestMapping("/apis/agent")
@Slf4j
public class AgentController {

    @Autowired
    private AgentService agentService;

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

        SseEmitter emitter = new SseEmitter(600_000L);

        agentService.chatStream(message, fileIdList)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event().name(event.get("type")).data(event.get("data")));
                    } catch (IOException e) { /* 客户端断开 */ }
                },
                error -> {
                    log.error("SSE 流式对话失败", error);
                    try {
                        Map<String, String> errResult = new LinkedHashMap<>();
                        errResult.put("type", "error");
                        errResult.put("message", error.getMessage());
                        emitter.send(SseEmitter.event().name("complete").data(JSON.toJSONString(errResult)));
                    } catch (Exception ignored) {}
                    emitter.complete();
                },
                () -> {
                    try {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("type", "complete");
                        result.put("chartOption", agentService.getLastChartOption());
                        result.put("conversationId", agentService.getOrCreateDefaultConversation());
                        result.put("tokenUsage", agentService.getLastTokenUsage());
                        emitter.send(SseEmitter.event().name("complete").data(JSON.toJSONString(result)));
                    } catch (Exception e) {
                        log.error("发送 complete 事件失败", e);
                    }
                    emitter.complete();
                }
            );

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
