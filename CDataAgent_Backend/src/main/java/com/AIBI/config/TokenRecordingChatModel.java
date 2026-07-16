package com.AIBI.config;

import com.AIBI.manager.TokenLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

/**
 * ChatModel 装饰器 — 透明记录每次模型调用的精确 token 消耗。
 * <p>
 * 在 Agent 框架内部，ChatModel 被用于每次 LLM 调用（含工具调用中的二次推理）。
 * 此装饰器在 call/stream 返回后从 ChatResponse.getMetadata().getUsage() 提取
 * 精确的 promptTokens 和 completionTokens，并通过 TokenLedger 累加到当前对话。
 * <p>
 * 与 {@link ConversationContext} 配合使用，在请求线程中传递当前 conversationId。
 * 此方案不需要修改 AgentServiceImpl 的流管道，不影响任何已有逻辑。
 */
@Slf4j
public class TokenRecordingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenLedger ledger;
    private final ConversationContext ctx;

    public TokenRecordingChatModel(ChatModel delegate, TokenLedger ledger, ConversationContext ctx) {
        this.delegate = delegate;
        this.ledger = ledger;
        this.ctx = ctx;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        recordUsage(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
                .doOnNext(this::recordUsage);
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void recordUsage(ChatResponse response) {
        Long cid = ctx.get();
        if (cid == null) return; // 未设置上下文时跳过（非对话场景的模型调用）

        Usage usage = response.getMetadata().getUsage();
        if (usage == null) return;

        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        if (promptTokens == null || completionTokens == null) return;

        try {
            ledger.recordRoundModelCall(cid, promptTokens, completionTokens);
        } catch (Exception e) {
            log.warn("Token记录失败", e);
        }
    }
}
