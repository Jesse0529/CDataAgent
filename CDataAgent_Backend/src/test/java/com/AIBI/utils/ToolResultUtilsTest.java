package com.AIBI.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolResultUtils 单元测试。
 * 覆盖：错误响应格式、错误类型识别、校验结果格式、边界/空值。
 */
@DisplayName("ToolResultUtils 工具类测试")
class ToolResultUtilsTest {

    // ─── jsonTypedError ─────────────────────────────────────────

    @Test
    @DisplayName("jsonTypedError 应返回标准错误 JSON 格式")
    void jsonTypedError_shouldReturnStandardFormat() {
        String result = ToolResultUtils.jsonTypedError("syntax", "列名不存在");
        JSONObject obj = JSON.parseObject(result);
        assertEquals("syntax", obj.getString("error"));
        assertEquals("列名不存在", obj.getString("message"));
    }

    @ParameterizedTest
    @CsvSource({
            "system, 系统异常",
            "timeout, 查询超时",
            "syntax, 语法错误",
            "not_found, 找不到数据",
            "validation, 校验失败"
    })
    @DisplayName("jsonTypedError 应支持所有错误类型")
    void jsonTypedError_shouldSupportAllTypes(String type, String message) {
        String result = ToolResultUtils.jsonTypedError(type, message);
        JSONObject obj = JSON.parseObject(result);
        assertEquals(type, obj.getString("error"));
        assertEquals(message, obj.getString("message"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("jsonTypedError 应处理空/Null 消息")
    void jsonTypedError_shouldHandleNullOrEmptyMessage(String msg) {
        String result = ToolResultUtils.jsonTypedError("system", msg);
        JSONObject obj = JSON.parseObject(result);
        assertEquals("system", obj.getString("error"));
        assertEquals(msg, obj.getString("message"));
    }

    // ─── jsonError ──────────────────────────────────────────────

    @Test
    @DisplayName("jsonError 应等价于 jsonTypedError(\"system\", msg)")
    void jsonError_shouldBeEquivalentToSystemTypedError() {
        String msg = "通用错误";
        assertEquals(
                ToolResultUtils.jsonTypedError("system", msg),
                ToolResultUtils.jsonError(msg)
        );
    }

    // ─── isError / isTransientError ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":\"syntax\",\"message\":\"错\"}",
            "{\"error\":\"system\",\"message\":\"错\"}",
            "{\"error\":\"timeout\",\"message\":\"超时\"}",
            "关键字 error 嵌套 {\"error\":\"inner\"}",
    })
    @DisplayName("isError 应返回 true：包含 error 字段的 JSON")
    void isError_shouldReturnTrueForErrorResponses(String response) {
        assertTrue(ToolResultUtils.isError(response));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "{}",
            "{\"valid\":true}",
            "正常 JSON 数组",
            "some random text without error marker"
    })
    @DisplayName("isError 应返回 false：无 error 字段")
    void isError_shouldReturnFalseForNonError(String response) {
        assertFalse(ToolResultUtils.isError(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":\"system\",\"message\":\"err\"}",
            "{\"error\":\"timeout\",\"message\":\"超时\"}"
    })
    @DisplayName("isTransientError 应识别 system/timeout 为可重试")
    void isTransientError_shouldReturnTrueForSystemAndTimeout(String response) {
        assertTrue(ToolResultUtils.isTransientError(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":\"syntax\",\"message\":\"err\"}",
            "{\"error\":\"not_found\",\"message\":\"err\"}",
            "{\"error\":\"validation\",\"message\":\"err\"}"
    })
    @DisplayName("isTransientError 不应识别 syntax/not_found/validation 为可重试")
    void isTransientError_shouldReturnFalseForNonTransient(String response) {
        assertFalse(ToolResultUtils.isTransientError(response));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("isTransientError 应处理 null 和空字符串")
    void isTransientError_shouldHandleNullOrEmpty(String response) {
        assertFalse(ToolResultUtils.isTransientError(response));
    }

    // ─── jsonValidResult ───────────────────────────────────────

    @Test
    @DisplayName("jsonValidResult 单条消息格式")
    void jsonValidResult_shouldReturnStandardFormat() {
        String result = ToolResultUtils.jsonValidResult(true, "校验通过");
        JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.getBoolean("valid"));
        assertEquals(List.of("校验通过"), obj.getJSONArray("issues").toList(String.class));
    }

    @Test
    @DisplayName("jsonValidResult 多条消息格式")
    void jsonValidResult_shouldSupportMultipleIssues() {
        String result = ToolResultUtils.jsonValidResult(false, List.of("问题1", "问题2"));
        JSONObject obj = JSON.parseObject(result);
        assertFalse(obj.getBoolean("valid"));
        assertEquals(List.of("问题1", "问题2"), obj.getJSONArray("issues").toList(String.class));
    }

    @Test
    @DisplayName("jsonValidResult null 消息应转为空 issues 数组")
    void jsonValidResult_shouldHandleNullMessage() {
        String result = ToolResultUtils.jsonValidResult(true, (String) null);
        JSONObject obj = JSON.parseObject(result);
        assertTrue(obj.getBoolean("valid"));
        assertTrue(obj.getJSONArray("issues").isEmpty());
    }

    @Test
    @DisplayName("jsonValidResult null 消息列表应转为空 issues 数组")
    void jsonValidResult_shouldHandleNullIssueList() {
        String result = ToolResultUtils.jsonValidResult(false, (List<String>) null);
        JSONObject obj = JSON.parseObject(result);
        assertFalse(obj.getBoolean("valid"));
        assertTrue(obj.getJSONArray("issues").isEmpty());
    }
}
