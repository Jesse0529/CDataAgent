package com.AIBI.aop;

import com.AIBI.agent.run.RunActivity;
import com.AIBI.agent.run.RunContext;
import com.AIBI.agent.run.RunContextHolder;
import com.AIBI.utils.ToolResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/**
 * 工具调用统计切面 — 记录每次 {@code @Tool} 方法的调用结果。
 * <p>
 * 格式：工具调用情况：工具={工具名}、状态={成功/失败/异常}、耗时={X}ms
 */
@Aspect
@Component
@Slf4j
public class ToolCallAspect {

    @Value("${agent.executor.max-tool-calls:50}")
    private int maxExecutorToolCalls;

    @Value("${agent.synthesizer.max-tool-calls:20}")
    private int maxSynthesizerToolCalls;

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundToolCall(ProceedingJoinPoint pjp) throws Throwable {
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        String toolName = className + "." + methodName;
        ActivityDescriptor descriptor = ActivityDescriptor.resolve(className, methodName);
        RunContext context = RunContextHolder.get();
        String stage = context != null ? context.getModelStage() : "unknown";
        if (shouldLimit(className, methodName, context, stage)) {
            int maxCalls = "synthesizer".equals(stage) ? maxSynthesizerToolCalls : maxExecutorToolCalls;
            if (maxCalls > 0 && !context.tryAcquireToolCall(stage, maxCalls)) {
                log.warn("工具调用已达上限：阶段={}、上限={}，请基于已有结果结束本阶段", stage, maxCalls);
                return ToolResultUtils.jsonTypedError("limit",
                        "本阶段工具调用已达到上限，请停止重试并基于已有结果完成本轮。");
            }
        }
        String activityId = context != null && descriptor != null
                && descriptor.visible()
                ? context.beginActivity(
                        descriptor.stage(), descriptor.toolKey(), descriptor.labelFor(RunActivity.State.RUNNING))
                : null;

        long startNanos = System.nanoTime();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            finishActivity(context, activityId, descriptor, RunActivity.State.FAILED);
            log.warn("{}工具调用异常：{}，耗时：{}ms",
                    toolName, e.getMessage(), elapsedMs);
            throw e;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 检查返回值是否为结构化错误
        if (result instanceof String resultStr && ToolResultUtils.isError(resultStr)) {
            String errType = extractErrorType(resultStr);
            finishActivity(context, activityId, descriptor, RunActivity.State.FAILED);
            log.warn("{}工具调用失败（{}），耗时：{}ms",
                    toolName, errType, elapsedMs);
        } else {
            finishActivity(context, activityId, descriptor, RunActivity.State.SUCCEEDED);
            log.info("{}工具调用成功，耗时：{}ms",
                    toolName, elapsedMs);
        }

        return result;
    }

    private static boolean shouldLimit(String className, String methodName, RunContext context, String stage) {
        if (context == null || (!"executor".equals(stage) && !"synthesizer".equals(stage))) return false;
        // 提交展示计划和校验已生成图表都是收口步骤，不能被前置探查耗尽配额后拦截。
        return !("PresentationSubmissionTool".equals(className) && "submitPresentation".equals(methodName))
                && !("ChartOutputTool".equals(className) && "validateChart".equals(methodName));
    }

    /**
     * 从 {@code {"error":"type","message":"..."}} 中提取错误类型。
     * 无法解析时返回 "unknown"。
     */
    private static String extractErrorType(String resultJson) {
        return ToolResultUtils.errorType(resultJson).orElse("unknown");
    }

    private static void finishActivity(RunContext context, String activityId,
                                       ActivityDescriptor descriptor, RunActivity.State state) {
        if (context == null || descriptor == null) return;
        if (activityId != null) {
            context.finishActivity(activityId, descriptor.stage(), descriptor.toolKey(), descriptor.labelFor(state), state);
        }
        if (descriptor.completesRequirementUnderstanding()) {
            context.completeRequirementUnderstanding(state);
        }
    }

    /** 工具实现名称仅在服务端映射，绝不出现在 SSE 或界面中。 */
    private record ActivityDescriptor(String stage, String toolKey, String toolLabel,
                                      boolean completesRequirementUnderstanding, boolean visible) {
        private String labelFor(RunActivity.State state) {
            return switch (toolKey) {
                case "data-load" -> stateLabel(state, "正在加载数据", "数据加载完成", "数据加载失败");
                case "data-schema" -> stateLabel(state, "正在读取数据结构", "数据结构读取完成", "数据结构读取失败");
                case "data-query" -> stateLabel(state, "正在查询数据", "数据查询完成", "数据查询失败");
                case "data-compute" -> stateLabel(state, "正在处理数据", "数据处理完成", "数据处理失败");
                case "presentation-submit" -> stateLabel(state, "正在整理分析结果", "分析结果已输出", "分析结果输出失败");
                case "chart-describe" -> stateLabel(state, "图表数据准备中", "图表数据准备就绪", "图表数据准备失败");
                case "chart-build" -> stateLabel(state, "图表生成中", "图表已生成", "图表生成失败");
                case "chart-validate" -> stateLabel(state, "图表输出校验中", "图表输出校验成功", "图表输出校验失败");
                default -> stateLabel(state, "正在调用" + toolLabel, toolLabel + "调用成功", toolLabel + "调用失败");
            };
        }

        private static String stateLabel(RunActivity.State state, String running, String succeeded, String failed) {
            return switch (state) {
                case RUNNING -> running;
                case SUCCEEDED -> succeeded;
                case FAILED -> failed;
            };
        }

        private static ActivityDescriptor resolve(String className, String methodName) {
            return switch (className) {
                case "DataLoadingTool" -> dataDescriptor(methodName);
                case "DuckDbQueryTool" -> new ActivityDescriptor("query", "data-query", "数据查询工具", false, true);
                case "PythonRunnerTool" -> new ActivityDescriptor("compute", "data-compute", "数据处理工具", false, true);
                case "PresentationSubmissionTool" -> new ActivityDescriptor("compose", "presentation-submit", "分析结果整理工具", false, true);
                case "ChartOutputTool" -> chartDescriptor(methodName);
                default -> null;
            };
        }

        private static ActivityDescriptor dataDescriptor(String methodName) {
            return switch (methodName) {
                case "declareIntent" -> new ActivityDescriptor("intent", "intent-declare", "意图声明工具", true, false);
                case "getSchema" -> new ActivityDescriptor("data", "data-schema", "数据结构读取工具", false, true);
                default -> new ActivityDescriptor("data", "data-load", "数据加载工具", false, true);
            };
        }

        private static ActivityDescriptor chartDescriptor(String methodName) {
            return switch (methodName) {
                case "describeData" -> new ActivityDescriptor("chart", "chart-describe", "图表数据准备工具", false, true);
                case "buildChart" -> new ActivityDescriptor("chart", "chart-build", "图表生成工具", false, true);
                case "validateChart" -> new ActivityDescriptor("validate", "chart-validate", "图表校验工具", false, true);
                default -> null;
            };
        }
    }
}
