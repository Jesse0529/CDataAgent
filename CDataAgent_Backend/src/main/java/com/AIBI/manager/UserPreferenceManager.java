package com.AIBI.manager;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 用户偏好管理器 — 基于 Redis Hash 存储跨对话偏好。
 * <p>
 * 自部署单用户模式，使用固定 key: user:pref:default
 */
@Slf4j
@Component
public class UserPreferenceManager {

    private static final String KEY = "user:pref:default";

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 保存一条偏好设置。
     */
    public void setPreference(String key, String value) {
        if (key == null || value == null) return;
        RMap<String, String> map = redissonClient.getMap(KEY);
        String old = map.put(key, value);
        log.debug("用户偏好已保存: key={}", key);
    }

    /**
     * 读取所有偏好。
     */
    public Map<String, String> getAllPreferences() {
        RMap<String, String> map = redissonClient.getMap(KEY);
        Map<String, String> result = map.readAllMap();
        return result.isEmpty() ? Map.of() : result;
    }

    /**
     * 读取单条偏好。
     */
    public String getPreference(String key) {
        if (key == null) return null;
        RMap<String, String> map = redissonClient.getMap(KEY);
        return map.get(key);
    }

    /**
     * 删除一条偏好。
     */
    public void removePreference(String key) {
        if (key == null) return;
        RMap<String, String> map = redissonClient.getMap(KEY);
        map.remove(key);
        log.debug("用户偏好已删除: key={}", key);
    }
}
