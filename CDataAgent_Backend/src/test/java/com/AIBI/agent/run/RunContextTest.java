package com.AIBI.agent.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunContextTest {

    @Test
    void recordsQueryReplayMetrics() {
        RunContext context = new RunContext("test-run", 1L);

        context.recordQueryReplay(12);
        context.recordQueryReplay(-1);

        RunContext.QueryReplayMetrics metrics = context.getQueryReplayMetrics();
        assertEquals(2, metrics.count());
        assertEquals(12, metrics.elapsedMs());
    }

    @Test
    void refusesToOverwriteAnotherRunContext() {
        RunContext first = new RunContext("run-1", 1L);
        RunContext second = new RunContext("run-2", 2L);
        RunContextHolder.set(first);

        try {
            assertThrows(IllegalStateException.class, () -> RunContextHolder.set(second));
            RunContextHolder.clear(second);
            assertEquals(first, RunContextHolder.get());
        } finally {
            RunContextHolder.clear(first);
        }
        assertNull(RunContextHolder.get());
    }
}
