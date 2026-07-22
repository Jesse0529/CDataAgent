package com.AIBI.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultUtilsTest {

    @Test
    void onlyRootErrorEnvelopeIsFailure() {
        assertTrue(ToolResultUtils.isError("{\"error\":\"system\",\"message\":\"失败\"}"));
        assertFalse(ToolResultUtils.isError("[{\"error\":\"system\",\"value\":1}]"));
        assertFalse(ToolResultUtils.isError("{\"error\":\"业务字段\",\"value\":1}"));
    }

    @Test
    void timeoutIsNotBlindlyRetryable() {
        assertTrue(ToolResultUtils.isTransientError("{\"error\":\"system\",\"message\":\"暂时不可用\"}"));
        assertFalse(ToolResultUtils.isTransientError("{\"error\":\"timeout\",\"message\":\"超时\"}"));
    }
}
