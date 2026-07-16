package com.AIBI.config;

import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 当前消息列表的保守 Token 计数器。
 * <p>
 * API 返回的 promptTokens 属于上一次模型请求，且包含 system prompt 与工具 schema，
 * 不能用于判断当前 Checkpoint 消息列表是否需要裁剪。此计数器只计算传入消息，供滑窗和
 * SummarizationHook 在模型调用前作预算决策；实际成本仍以 TokenLedger 的 API usage 为准。
 */
@Component
@Slf4j
public class ExactTokenCounter implements TokenCounter {

    private static final int DEFAULT_CHARS_PER_TOKEN = 2;
    private static final int SUMMARY_WARNING_TOKENS = 96_000;

    private volatile boolean summaryWarningLogged;

    /**
     * 保留兼容调用。预调用裁剪不再依赖上一请求的 API usage。
     */
    public void setCurrentConversationId(Long cid) {
        summaryWarningLogged = false;
    }

    /**
     * 保留兼容调用。
     */
    public void clear() {
        summaryWarningLogged = false;
    }

    @Override
    public int countTokens(List<Message> messages) {
        int tokens = estimateByChars(messages, DEFAULT_CHARS_PER_TOKEN);
        if (tokens >= SUMMARY_WARNING_TOKENS && !summaryWarningLogged) {
            summaryWarningLogged = true;
            log.info("摘要上下文接近阈值：消息数={}、估算Token={}、阈值=128000",
                    messages.size(), tokens);
        }
        return tokens;
    }

    static int estimateByChars(List<Message> messages, int charsPerToken) {
        int total = 0;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
                    total += ceilDivide(response.responseData().length(), charsPerToken);
                }
                continue;
            }

            if (msg.getText() != null) {
                total += ceilDivide(msg.getText().length(), charsPerToken);
            }
            if (msg instanceof AssistantMessage assistantMessage) {
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    total += ceilDivide(toolCall.arguments().length(), charsPerToken);
                }
            }
        }
        return total;
    }

    private static int ceilDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
