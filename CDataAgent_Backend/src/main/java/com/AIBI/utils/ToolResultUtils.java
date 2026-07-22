package com.AIBI.utils;

import com.alibaba.fastjson2.JSONObject;

import java.util.Optional;
import java.util.Set;

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
 *   <dt>precondition</dt><dd>缺少前置状态或引用，需先完成对应步骤</dd>
 *   <dt>timeout</dt><dd>执行超时，需简化查询</dd>
 *   <dt>system</dt><dd>引擎/系统异常，可重试</dd>
 *   <dt>schema</dt><dd>表结构或字段不匹配</dd>
 *   <dt>limit</dt><dd>结果或资源超过限制</dd>
 *   <dt>output_key_conflict</dt><dd>输出键已被其他查询占用</dd>
 * </dl>
 */
public final class ToolResultUtils {

    private ToolResultUtils() {}

    public static final String ERROR_SYNTAX = "syntax";
    public static final String ERROR_TIMEOUT = "timeout";
    public static final String ERROR_SYSTEM = "system";
    public static final String ERROR_PRECONDITION = "precondition";
    public static final String ERROR_SCHEMA = "schema";
    public static final String ERROR_LIMIT = "limit";
    public static final String ERROR_OUTPUT_KEY_CONFLICT = "output_key_conflict";

    private static final Set<String> ERROR_TYPES = Set.of(
            ERROR_SYNTAX, ERROR_TIMEOUT, ERROR_SYSTEM, ERROR_PRECONDITION,
            ERROR_SCHEMA, ERROR_LIMIT, ERROR_OUTPUT_KEY_CONFLICT);

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

    /** 只有 JSON 根对象中的合法错误类型才表示工具失败。 */
    public static boolean isError(String response) {
        return errorType(response).isPresent();
    }

    public static Optional<String> errorType(String response) {
        if (response == null || response.isBlank() || !response.stripLeading().startsWith("{")) {
            return Optional.empty();
        }
        try {
            JSONObject object = JSONObject.parseObject(response);
            String type = object == null ? null : object.getString("error");
            return ERROR_TYPES.contains(type) ? Optional.of(type) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** 只有 system 可使用相同参数进行一次技术重试。 */
    public static boolean isTransientError(String response) {
        return errorType(response).filter(ERROR_SYSTEM::equals).isPresent();
    }
}
