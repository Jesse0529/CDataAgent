package com.AIBI.utils;

import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * Agent 工具返回值工具类。
 * <p>
 * 统一所有工具的返回值格式，分为三级：
 * <ul>
 *   <li>{@link #jsonTypedError(String, String)} — 带类型的错误响应</li>
 *   <li>{@link #jsonError(String)} — 通用系统错误</li>
 * </ul>
 * <p>
 * 错误类型编码：
 * <dl>
 *   <dt>syntax</dt><dd>输入错误（列名/SQL/参数），修正后可重试</dd>
 *   <dt>timeout</dt><dd>执行超时，需简化查询</dd>
 *   <dt>system</dt><dd>引擎/系统异常，可重试</dd>
 * </dl>
 */
public final class ToolResultUtils {

    private ToolResultUtils() {}

    public static final String ERROR_SYNTAX = "syntax";
    public static final String ERROR_TIMEOUT = "timeout";
    public static final String ERROR_SYSTEM = "system";

    /**
     * 返回带类型的错误响应。
     * 格式：{"error":"type","message":"human readable"}
     */
    public static String jsonTypedError(String type, String message) {
        JSONObject err = new JSONObject();
        err.put("error", type);
        err.put("message", message);
        return err.toJSONString();
    }

    /**
     * 返回系统级错误响应，等价于 jsonTypedError("system", message)。
     */
    public static String jsonError(String message) {
        return jsonTypedError(ERROR_SYSTEM, message);
    }

    /**
     * 判断响应字符串是否包含 "error" 字段。
     */
    public static boolean isError(String response) {
        return response != null && response.contains("\"error\"");
    }

    /**
     * 判断是否为可重试的瞬态错误（system / timeout）。
     */
    public static boolean isTransientError(String response) {
        if (response == null) return false;
        return response.contains("\"error\":\"system\"")
                || response.contains("\"error\":\"timeout\"");
    }
}
