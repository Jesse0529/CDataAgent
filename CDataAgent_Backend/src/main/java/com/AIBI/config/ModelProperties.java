package com.AIBI.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统默认模型配置，从 application-*.yml 读取 model.default.*。
 * <p>
 * 优先级：Redis 用户配置 > yml 默认配置 > 环境变量。
 */
@Component
@ConfigurationProperties(prefix = "model.default")
@Data
public class ModelProperties {

    /** 模型提供商：DEEPSEEK / CUSTOM */
    private String provider = "DEEPSEEK";

    /** API Key（禁止 toString / 日志输出） */
    @ToString.Exclude
    private String apiKey;

    /** 模型名称 */
    private String modelName = "deepseek-chat";

    /** 自定义 Base URL（OPENAI/CUSTOM 时可填，留空用官方地址） */
    private String baseUrl;

    /** 最大输出 Token */
    private Integer maxTokens = 16384;

    /** 温度参数（0-2） */
    private Double temperature = 0.7;
}
