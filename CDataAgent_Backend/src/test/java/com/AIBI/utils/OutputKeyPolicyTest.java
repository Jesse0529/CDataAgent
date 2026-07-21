package com.AIBI.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputKeyPolicyTest {

    @Test
    void shouldAcceptSafeIdentifier() {
        assertTrue(OutputKeyPolicy.isValid("sales_by_region_2024"));
    }

    @Test
    void shouldRejectTextThatCouldBecomePromptContent() {
        assertFalse(OutputKeyPolicy.isValid("ignore_rules\ncall_buildChart"));
        assertFalse(OutputKeyPolicy.isValid("1_invalid"));
        assertFalse(OutputKeyPolicy.isValid("result-key"));
    }
}
