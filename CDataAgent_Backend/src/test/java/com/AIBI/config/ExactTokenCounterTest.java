package com.AIBI.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExactTokenCounterTest {

    @Test
    void countsCurrentMessagesAndToolPayloadsConservatively() {
        List<Message> messages = List.of(
                new UserMessage("abc"),
                AssistantMessage.builder()
                        .content("de")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "query", "123")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call", "query", "12345")))
                        .build());

        assertEquals(8, ExactTokenCounter.estimateByChars(messages, 2));
    }
}
