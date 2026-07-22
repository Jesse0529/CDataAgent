package com.AIBI.config;

import com.AIBI.AgentTool.*;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Agent 配置 — 三层 Agent 架构（Plan-Execute-Synthesize）。
 * <p>
 * 三个 ReactAgent 在同一文件中注册，各司其职：
 * <ul>
 *   <li>{@code plannerAgent} — 无工具，纯推理，产出执行计划 JSON</li>
 *   <li>{@code executorAgent} — 持有 5 个数据工具，按计划逐步执行</li>
 *   <li>{@code synthesizerAgent} — 持有 3 个输出工具，生成图表和结论</li>
 * </ul>
 * <p>
 * Agent 间通过 {@code AnalysisState} 传递结构化数据，不通过 LLM 上下文。
 * 对话记忆由 {@link RedisSaver} checkpoint 管理。
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Autowired
    private AgentPromptConfig promptConfig;

    @Value("${agent.executor.timeout-seconds:90}")
    private int executorTimeout;

    @Value("${agent.synthesizer.timeout-seconds:30}")
    private int synthesizerTimeout;

    @Value("${sandbox.enabled:false}")
    private boolean sandboxEnabled;

    // ─── 拦截器 ────────────────────────────────────────────────

    private ToolErrorInterceptor toolErrorHandler() {
        return ToolErrorInterceptor.builder().build();
    }

    // ─── 基础设施 Bean ───────────────────────────────────────

    @Bean
    public BaseCheckpointSaver redisSaver(RedissonClient redissonClient) {
        var inner = RedisSaver.builder()
                .redisson(redissonClient)
                .stateSerializer(new SpringAIStateSerializer())
                .build();
        log.info("RedisSaver已初始化，TTL=3天");
        return new TtlRedisSaver(inner, redissonClient, Duration.ofDays(3));
    }

    @Bean
    public SummarizationHook summarizationHook(ChatModel chatModel,
                                               ExactTokenCounter exactTokenCounter) {
        log.info("摘要钩子已初始化，阈值128k tokens");
        return SummarizationHook.builder()
                .model(chatModel)
                .tokenCounter(exactTokenCounter)
                .maxTokensBeforeSummary(128_000)
                .messagesToKeep(6)
                .keepFirstUserMessage(true)
                .build();
    }

    // ─── Executor Agent — 自主执行分析或对话 ──────────────────────

    /**
     * Executor: 持有数据工具，严格按计划逐步执行。
     * 使用独立的 System Prompt（agent-executor.txt）。
     * 沙箱通过 {@code ObjectProvider} 可选注入。
     */
    @Bean
    public ReactAgent executorAgent(ChatModel chatModel,
                                    DataLoadingTool dataLoadingTool,
                                    DuckDbQueryTool duckDbQueryTool,
                                    PythonRunnerTool pythonRunnerTool,
                                    PreferenceTool preferenceTool,
                                    PresentationSubmissionTool presentationSubmissionTool,
                                    BaseCheckpointSaver redisSaver,
                                    SummarizationHook summarizationHook) {
        var builder = ReactAgent.builder()
                .name("Executor")
                .description("数据分析助手——自主决策，与用户对话并执行数据分析")
                .model(chatModel)
                .systemPrompt(promptConfig.getExecutorPrompt())
                .saver(redisSaver)
                .hooks(summarizationHook)
                .toolExecutionTimeout(Duration.ofSeconds(executorTimeout));
        if (sandboxEnabled) {
            builder.interceptors(toolErrorHandler());
            builder.methodTools(dataLoadingTool, duckDbQueryTool, pythonRunnerTool,
                    preferenceTool, presentationSubmissionTool);
        } else {
            builder.interceptors(toolErrorHandler());
            builder.methodTools(dataLoadingTool, duckDbQueryTool, preferenceTool, presentationSubmissionTool);
        }
        ReactAgent agent = builder.build();

        log.info("执行器Agent就绪（Python工具启用={}）", sandboxEnabled);
        return agent;
    }

    // ─── Synthesizer Agent — 结果合成与输出 ──────────────────────

    /**
     * Synthesizer: 持有 2 个输出工具（图表构建 + 校验），负责将分析结果转化为用户友好输出。
     * 使用独立的 System Prompt（agent-synthesizer.txt）。
     */
    @Bean
    public ReactAgent synthesizerAgent(ChatModel chatModel,
                                       ChartOutputTool chartOutputTool,
                                       PreferenceTool preferenceTool) {
        ReactAgent agent = ReactAgent.builder()
                .name("Synthesizer")
                .description("数据分析报告专家——生成图表配置和分析结论")
                .model(chatModel)
                .systemPrompt(promptConfig.getSynthesizerPrompt())
                .methodTools(chartOutputTool, preferenceTool)
                .toolExecutionTimeout(Duration.ofSeconds(synthesizerTimeout))
                .interceptors(toolErrorHandler())
                .build();

        log.info("合成器Agent就绪（2个工具）");
        return agent;
    }
}
