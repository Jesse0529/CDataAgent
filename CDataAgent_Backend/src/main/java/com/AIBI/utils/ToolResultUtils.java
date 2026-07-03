package com.AIBI.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * Agent 工具返回值工具类。
 * <p>
 * 统一所有工具的返回值格式，分为三类：
 * <ul>
 *   <li>{@code jsonTypedError(type, message)} — 带类型的错误响应</li>
 *   <li>{@code jsonError(message)} — 通用系统错误（等价于 jsonTypedError("system", message)）</li>
 *   <li>{@code jsonValidResult(valid, issues)} — 校验结果（仅 validateChart 使用）</li>
 * </ul>
 * <p>
 * 错误类型编码：
 * <dl>
 *   <dt>syntax</dt><dd>输入错误（列名/SQL/参数），修正后可重试</dd>
 *   <dt>timeout</dt><dd>执行超时，需简化查询</dd>
 *   <dt>not_found</dt><dd>数据/引用不存在，需检查名称</dd>
 *   <dt>system</dt><dd>引擎/系统异常，可重试</dd>
 *   <dt>validation</dt><dd>校验失败，需修改输入</dd>
 * </dl>
 */
public final class ToolResultUtils {

    private ToolResultUtils() {}

    /** 错误类型常量 */
    public static final String ERROR_SYNTAX = "syntax";
    public static final String ERROR_TIMEOUT = "timeout";
    public static final String ERROR_NOT_FOUND = "not_found";
    public static final String ERROR_SYSTEM = "system";
    public static final String ERROR_VALIDATION = "validation";

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
     * 返回通用系统错误响应（向后兼容）。
     * 等价于 jsonTypedError("system", message)。
     */
    public static String jsonError(String message) {
        return jsonTypedError(ERROR_SYSTEM, message);
    }

    /**
     * 返回校验结果响应（单条消息）。
     * 格式：{"valid":true/false,"issues":["msg"]}
     */
    public static String jsonValidResult(boolean valid, String message) {
        JSONObject r = new JSONObject();
        r.put("valid", valid);
        JSONArray arr = new JSONArray();
        if (message != null) arr.add(message);
        r.put("issues", arr);
        return r.toJSONString();
    }

    /**
     * 返回校验结果响应（多条消息）。
     * 格式：{"valid":true/false,"issues":["...","..."]}
     */
    public static String jsonValidResult(boolean valid, List<String> issues) {
        JSONObject r = new JSONObject();
        r.put("valid", valid);
        JSONArray arr = new JSONArray();
        if (issues != null) issues.forEach(arr::add);
        r.put("issues", arr);
        return r.toJSONString();
    }

    /**
     * 判断响应字符串是否包含错误。
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
