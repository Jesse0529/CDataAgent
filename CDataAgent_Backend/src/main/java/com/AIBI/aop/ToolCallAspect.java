package com.AIBI.aop;

import com.AIBI.agent.run.RunActivity;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
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
        ActivityDescriptor descriptor = ActivityDescriptor.resolve(className, methodName);
        RunContext context = RunContextHolder.get();
        String activityId = context != null && descriptor != null
                ? context.beginActivity(descriptor.stage(), descriptor.runningLabel())
                : null;

        long startNanos = System.nanoTime();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            finishActivity(context, activityId, descriptor, RunActivity.State.FAILED);
            log.warn("工具调用异常：工具={}、错误=\"{}\"、耗时={}ms",
                    toolName, e.getMessage(), elapsedMs);
            throw e;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 检查返回值是否为结构化错误
        if (result instanceof String resultStr && ToolResultUtils.isError(resultStr)) {
            String errType = extractErrorType(resultStr);
            finishActivity(context, activityId, descriptor, RunActivity.State.FAILED);
            log.warn("工具调用失败：工具={}、错误类型={}、耗时={}ms",
                    toolName, errType, elapsedMs);
        } else {
            finishActivity(context, activityId, descriptor, RunActivity.State.SUCCEEDED);
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

    private static void finishActivity(RunContext context, String activityId,
                                       ActivityDescriptor descriptor, RunActivity.State state) {
        if (context != null && activityId != null && descriptor != null) {
            context.finishActivity(activityId, descriptor.stage(), descriptor.finishLabel(), state);
        }
    }

    /** 工具实现名称仅在服务端映射，绝不出现在 SSE 或界面中。 */
    private record ActivityDescriptor(String stage, String runningLabel, String finishLabel) {
        private static ActivityDescriptor resolve(String className, String methodName) {
            return switch (className) {
                case "DataLoadingTool" -> new ActivityDescriptor("data", "正在加载数据", "数据加载完成");
                case "DuckDbQueryTool" -> new ActivityDescriptor("query", "正在查询数据", "数据查询完成");
                case "PythonRunnerTool" -> new ActivityDescriptor("compute", "正在处理数据", "数据处理完成");
                case "PresentationSubmissionTool" -> new ActivityDescriptor("compose", "正在整理分析结果", "分析结果已整理");
                case "ChartOutputTool" -> chartDescriptor(methodName);
                default -> null;
            };
        }

        private static ActivityDescriptor chartDescriptor(String methodName) {
            return switch (methodName) {
                case "describeData" -> new ActivityDescriptor("chart", "正在准备图表数据", "图表数据已准备");
                case "buildChart" -> new ActivityDescriptor("chart", "正在生成图表", "图表已生成");
                case "validateChart" -> new ActivityDescriptor("validate", "正在校验图表", "图表校验完成");
                default -> null;
            };
        }
    }
}
