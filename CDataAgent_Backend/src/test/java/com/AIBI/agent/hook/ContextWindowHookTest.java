package com.AIBI.agent.hook;

import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextWindowHookTest {

    private static final TokenCounter ONE_TOKEN_PER_MESSAGE = messages -> messages.size();

    @Test
    void keepsTwoNewestCompleteTurnsWhenBudgetIsExceeded() {
        ContextWindowHook hook = new ContextWindowHook(ONE_TOKEN_PER_MESSAGE, 1);
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("old request"));
        messages.add(new AssistantMessage("old response"));

        messages.add(new UserMessage("tool request"));
        messages.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tool-1", "function", "runDuckdb", "{}")))
                .build());
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("tool-1", "runDuckdb", "tool result")))
                .build());
        messages.add(new AssistantMessage("tool completed"));

        messages.add(new UserMessage("latest request"));
        messages.add(new AssistantMessage("latest response"));

        List<Message> compacted = hook.compact(messages);

        assertEquals(6, compacted.size());
        assertFalse(compacted.stream().map(Message::getText).anyMatch("old request"::equals));
        assertTrue(compacted.stream().anyMatch(AssistantMessage.class::isInstance));
        assertTrue(compacted.stream().anyMatch(ToolResponseMessage.class::isInstance));
        assertEquals("latest request", compacted.get(4).getText());
    }

    @Test
    void preservesPinnedSummaryMessage() {
        ContextWindowHook hook = new ContextWindowHook(ONE_TOKEN_PER_MESSAGE, 1);
        List<Message> messages = List.of(
                new SystemMessage("Summary of previous conversation"),
                new UserMessage("first"), new AssistantMessage("first answer"),
                new UserMessage("second"), new AssistantMessage("second answer"),
                new UserMessage("third"), new AssistantMessage("third answer"));

        List<Message> compacted = hook.compact(messages);

        assertEquals("Summary of previous conversation", compacted.get(0).getText());
        assertEquals("second", compacted.get(1).getText());
        assertEquals("third", compacted.get(3).getText());
    }
}
