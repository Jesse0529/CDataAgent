package com.AIBI.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Agent 工具调用结果缓存管理器。
 * <p>
 * 基于 Redisson RMapCache，支持按 key 设置 TTL，
 * 用于缓存确定性工具调用结果，减少重复 LLM/DB 调用。
 */
@Slf4j
@Component
public class ToolCacheManager {

    /** 默认缓存 Map 名称 */
    private static final String CACHE_MAP = "agent-tool-cache";

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 根据缓存 key 获取结果。
     *
     * @return 缓存值（JSON 字符串），未命中时返回 null
     */
    public String get(String key) {
        if (key == null) return null;
        RMapCache<String, String> cache = redissonClient.getMapCache(CACHE_MAP);
        String value = cache.get(key);
        if (value != null) {
            log.debug("缓存命中: key={}", key);
        }
        return value;
    }

    /**
     * 写入缓存。
     *
     * @param key        缓存 key
     * @param value      缓存值（JSON 字符串）
     * @param ttlSeconds 过期时间（秒）
     */
    public void put(String key, String value, long ttlSeconds) {
        if (key == null || value == null) return;
        RMapCache<String, String> cache = redissonClient.getMapCache(CACHE_MAP);
        cache.put(key, value, ttlSeconds, TimeUnit.SECONDS);
        log.debug("缓存写入: key={}, ttl={}s", key, ttlSeconds);
    }

    /**
     * 构造缓存 key。
     *
     * @param toolName 工具名称（如 validateEChartsJson）
     * @param args     参数列表，用于计算 hash
     * @return 缓存 key
     */
    public String buildKey(String toolName, Object... args) {
        StringBuilder sb = new StringBuilder("tool:");
        sb.append(toolName).append(":");
        for (Object arg : args) {
            if (arg instanceof String) {
                // 对长字符串取 hash 避免 key 过长
                String s = (String) arg;
                sb.append(s.length() > 128 ? Objects.hash(s) : s);
            } else {
                sb.append(Objects.hash(arg));
            }
            sb.append("|");
        }
        return sb.toString();
    }

    /**
     * 按前缀批量失效缓存。
     * 遍历所有 key，删除以 prefix 开头的条目。
     */
    public void evictByPrefix(String prefix) {
        if (prefix == null) return;
        RMapCache<String, String> cache = redissonClient.getMapCache(CACHE_MAP);
        cache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .forEach(k -> {
                    cache.remove(k);
                    log.debug("缓存淘汰: key={}", k);
                });
    }
}
