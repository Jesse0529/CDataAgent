package com.AIBI.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingUsageAccumulatorTest {

    @Test
    void ignoresZeroPlaceholderThenRecordsFinalUsageForSameResponse() {
        StreamingUsageAccumulator accumulator = new StreamingUsageAccumulator();

        assertTrue(accumulator.accept("resp-1", 0, 0).isEmpty());
        StreamingUsageAccumulator.UsageDelta delta = accumulator.accept("resp-1", 120, 35).orElseThrow();

        assertEquals(120, delta.inputTokens());
        assertEquals(35, delta.outputTokens());
    }

    @Test
    void recordsOnlyGrowthForRepeatedCumulativeUsage() {
        StreamingUsageAccumulator accumulator = new StreamingUsageAccumulator();

        assertEquals(105, total(accumulator.accept("resp-1", 100, 5).orElseThrow()));
        assertEquals(7, total(accumulator.accept("resp-1", 100, 12).orElseThrow()));
        assertTrue(accumulator.accept("resp-1", 100, 12).isEmpty());
    }

    private int total(StreamingUsageAccumulator.UsageDelta delta) {
        return delta.inputTokens() + delta.outputTokens();
    }
}
