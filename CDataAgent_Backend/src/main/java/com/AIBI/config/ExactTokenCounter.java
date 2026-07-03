package com.AIBI.config;

import com.AIBI.manager.TokenLedger;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 TokenLedger 精确值的 TokenCounter — 替代框架默认的字符估算（text.length()/4）。
 * <p>
 * 每次模型调用后，{@link TokenLedger#recordRoundModelCall} 会将精确的 inputTokens
 * 写入 Redis {@code conv:ledger:{cid}} 的 {@code lastInputTokens} 字段。
 * 下一轮 {@link SummarizationHook} 触发时，此计数器优先使用该精确值判断是否需要压缩，
 * 仅在精确值不可用（新对话、Redis 连接问题）时回退到字符估算。
 * <p>
 * conversationId 由 {@code AgentServiceImpl} 在请求开始时传入、结束时清除，
 * 通过 {@link #setCurrentConversationId(Long)} / {@link #clear()} 管理。
 */
@Component
public class ExactTokenCounter implements TokenCounter {

    private static final int DEFAULT_CHARS_PER_TOKEN = 4;

    @Autowired
    private TokenLedger tokenLedger;

    private final ThreadLocal<Long> conversationIdHolder = new ThreadLocal<>();

    public void setCurrentConversationId(Long cid) {
        conversationIdHolder.set(cid);
    }

    public void clear() {
        conversationIdHolder.remove();
    }

    @Override
    public int countTokens(List<Message> messages) {
        // 1. 优先使用精确值
        Long cid = conversationIdHolder.get();
        if (cid != null) {
            int exactTokens = tokenLedger.getCurrentMessageListTokens(cid);
            if (exactTokens > 0) {
                return exactTokens;
            }
        }

        // 2. 回退：字符估算（与框架默认行为一致）
        return estimateByChars(messages, DEFAULT_CHARS_PER_TOKEN);
    }

    // ─── 回退估算 ───────────────────────────────────────────

    /**
     * 与框架 {@link TokenCounter#approximateMsgCounter(int)} 实现一致的字符估算逻辑。
     */
    static int estimateByChars(List<Message> messages, int charsPerToken) {
        int total = 0;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    total += resp.responseData().length() / charsPerToken;
                }
            } else {
                // AssistantMessage：包含 text + tool_calls arguments
                if (msg.getText() != null) {
                    total += msg.getText().length() / charsPerToken;
                }
                if (msg instanceof AssistantMessage am) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        total += tc.arguments().length() / charsPerToken;
                    }
                }
            }
        }
        return total;
    }
}
