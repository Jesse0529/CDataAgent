package com.AIBI.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisStateTest {

    @Test
    void clearsOnlySpecifiedConversationState() {
        AnalysisState state = new AnalysisState();
        state.setCurrentThreadId("conversation-a");
        state.addData("result-a", "[{\"value\":1}]");

        state.setCurrentThreadId("conversation-b");
        state.addData("result-b", "[{\"value\":2}]");

        state.clearByConversation("conversation-a");

        assertEquals("conversation-b", state.getCurrentThreadId());
        assertEquals("[{\"value\":2}]", state.getDataByKey("result-b"));

        state.setCurrentThreadId("conversation-a");
        assertNull(state.getDataByKey("result-a"));
    }
}
