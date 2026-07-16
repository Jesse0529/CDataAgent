package com.AIBI.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStateStoreTest {

    @Test
    void retainsLatestEntriesWhenTotalCapacityExceeded() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("first", "a".repeat(60 * 1024));
        data.put("second", "b".repeat(40 * 1024));
        data.put("third", "c".repeat(30 * 1024));

        AnalysisStateStore.trimDataIndexToCapacity(data, 100 * 1024);

        assertFalse(data.containsKey("first"));
        assertTrue(data.containsKey("second"));
        assertTrue(data.containsKey("third"));
        assertEquals(2, data.size());
    }
}
