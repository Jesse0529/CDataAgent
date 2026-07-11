package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Agent Prompt 配置：从 classpath:prompts/ 加载 Agent System Prompt。
 */
@Slf4j
@Configuration
public class AgentPromptConfig {

    @Value("classpath:prompts/agent-executor.md")
    private Resource executorPromptResource;

    @Value("classpath:prompts/agent-synthesizer.md")
    private Resource synthesizerPromptResource;

    public String getExecutorPrompt() { return readResource(executorPromptResource); }
    public String getSynthesizerPrompt() { return readResource(synthesizerPromptResource); }

    private String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.error("读取 prompt 文件失败: {}", resource.getFilename(), e);
            return "";
        }
    }
}
