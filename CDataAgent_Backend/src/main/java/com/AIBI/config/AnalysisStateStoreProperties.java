package com.AIBI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AnalysisState Redis 持久化配置。
 * <p>
 * 从 application-*.yml 读取 agent.context.analysis-state-persistence.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.context.analysis-state-persistence")
public class AnalysisStateStoreProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 元数据 TTL（天），与 Redis Checkpoint 保持一致 */
    private int metaTtlDays = 3;

    /** SQL 结果数据 TTL（分钟），较短，过期自动重查 */
    private int dataTtlMinutes = 60;

    /** dataIndex 总容量上限，超限时保留较新的结果。 */
    private int maxDataTotalBytes = 1024 * 1024;

    /** 单条 data 最大字节数，超限不持久化 */
    private int maxDataEntryBytes = 50 * 1024;
}
