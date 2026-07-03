package com.AIBI.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redisson 客户端配置。
 * <p>
 * 支持 Redis 有密码/无密码两种场景：
 * <ul>
 *   <li>无密码：不在配置文件中设置 password 字段即可</li>
 *   <li>有密码：在 yml 中配置 spring.data.redis.password，自动生效</li>
 * </ul>
 */
@Configuration
public class RedissonConfig {

    @Autowired
    private RedissonProperties redissonProperties;

    @Lazy
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String password = redissonProperties.getPassword();
        config.useSingleServer()
                .setDatabase(redissonProperties.getDatabase())
                .setAddress("redis://" + redissonProperties.getHost() + ":" + redissonProperties.getPort());
        // 仅当显式配置密码时才设置，无密码场景不受影响
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
