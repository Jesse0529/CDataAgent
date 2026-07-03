package com.AIBI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.data.redis")
@Slf4j
public class RedissonProperties {

    @PostConstruct
    public void init() {
        log.debug("RedissonProperties 绑定值: host={}, port={}, database={}", host, port, database);
    }
    private Integer database;
    private String host;
    private Integer port;
    private String password;
}