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
 * 日志格式统一为 {@code ToolStat: tool=ClassName.method, status=SUCCESS|FAILED, ...}，
 * 方便通过 grep 聚合统计调用成功率。
 * <p>
 * 覆盖范围：所有通过 {@code .methodTools()} 注册的 {@code @Tool} 方法，
 * 包括 DataLoadingTool、DuckDbQueryTool、ChartOutputTool、PythonRunnerTool、PreferenceTool。
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
            log.warn("ToolStat: tool={}, status=EXCEPTION, error=\"{}\", elapsed={}ms",
                    toolName, e.getMessage(), elapsedMs);
            throw e;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 检查返回值是否为结构化错误
        if (result instanceof String resultStr && ToolResultUtils.isError(resultStr)) {
            String errType = extractErrorType(resultStr);
            log.warn("ToolStat: tool={}, status=FAILED, errType={}, elapsed={}ms",
                    toolName, errType, elapsedMs);
        } else {
            log.info("ToolStat: tool={}, status=SUCCESS, elapsed={}ms",
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
