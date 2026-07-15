package com.AIBI.agent.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Executor 短期上下文滑窗。
 * <p>
 * 按用户回合而不是单条消息裁剪，因而 Assistant 的 tool-call 与后续 ToolResponse
 * 始终位于同一回合，避免向模型提交断裂的工具交互。
 */
public class ContextWindowHook extends MessagesModelHook {

    static final int DEFAULT_TOKEN_BUDGET = 32_000;
    static final int MIN_TURNS_TO_KEEP = 2;

    private final TokenCounter tokenCounter;
    private final int tokenBudget;

    public ContextWindowHook(TokenCounter tokenCounter) {
        this(tokenCounter, DEFAULT_TOKEN_BUDGET);
    }

    ContextWindowHook(TokenCounter tokenCounter, int tokenBudget) {
        this.tokenCounter = tokenCounter;
        this.tokenBudget = tokenBudget;
    }

    @Override
    public String getName() {
        return "ContextWindow";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        if (messages == null || messages.isEmpty()) {
            return new AgentCommand(messages);
        }

        List<Message> compacted = compact(messages);
        if (compacted.size() == messages.size()) {
            return new AgentCommand(messages);
        }
        return new AgentCommand(compacted, UpdatePolicy.REPLACE);
    }

    List<Message> compact(List<Message> messages) {
        List<Message> pinned = new ArrayList<>();
        List<List<Message>> turns = new ArrayList<>();
        List<Message> currentTurn = null;

        for (Message message : messages) {
            if (message instanceof UserMessage) {
                currentTurn = new ArrayList<>();
                turns.add(currentTurn);
            }
            if (currentTurn == null) {
                // SummarizationHook 生成的 SystemMessage 等前置消息必须保留。
                pinned.add(message);
            } else {
                currentTurn.add(message);
            }
        }

        if (turns.size() <= MIN_TURNS_TO_KEEP) {
            return messages;
        }

        Deque<List<Message>> retainedTurns = new ArrayDeque<>();
        int usedTokens = tokenCounter.countTokens(pinned);
        for (int i = turns.size() - 1; i >= 0; i--) {
            List<Message> turn = turns.get(i);
            int turnTokens = tokenCounter.countTokens(turn);
            if (retainedTurns.size() < MIN_TURNS_TO_KEEP || usedTokens + turnTokens <= tokenBudget) {
                retainedTurns.addFirst(turn);
                usedTokens += turnTokens;
            } else {
                break;
            }
        }

        List<Message> result = new ArrayList<>(pinned.size() + messages.size());
        result.addAll(pinned);
        retainedTurns.forEach(result::addAll);
        return result;
    }
}
