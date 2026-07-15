package com.AIBI.aop;

import com.AIBI.utils.ToolResultUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 工具调用统计切面 — 记录每次 {@code @Tool} 方法的调用结果。
 * <p>
 * 格式：工具调用情况：工具={工具名}、状态={成功/失败/异常}、耗时={X}ms
 */
@Aspect
@Component
@Slf4j
public class ToolCallAspect {

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundToolCall(ProceedingJoinPoint pjp) throws Throwable {
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        String toolName = className + "." + methodName;

        long startNanos = System.nanoTime();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("工具调用异常：工具={}、错误=\"{}\"、耗时={}ms",
                    toolName, e.getMessage(), elapsedMs);
            throw e;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 检查返回值是否为结构化错误
        if (result instanceof String resultStr && ToolResultUtils.isError(resultStr)) {
            String errType = extractErrorType(resultStr);
            log.warn("工具调用失败：工具={}、错误类型={}、耗时={}ms",
                    toolName, errType, elapsedMs);
        } else {
            log.info("工具调用成功：工具={}、耗时={}ms",
                    toolName, elapsedMs);
        }

        return result;
    }

    /**
     * 从 {@code {"error":"type","message":"..."}} 中提取错误类型。
     * 无法解析时返回 "unknown"。
     */
    private static String extractErrorType(String resultJson) {
        try {
            JSONObject obj = JSON.parseObject(resultJson);
            if (obj != null) {
                String type = obj.getString("error");
                if (type != null) return type;
            }
        } catch (Exception ignored) {
            // 非 JSON 或解析失败，不阻塞
        }
        return "unknown";
    }
}
