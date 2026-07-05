package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 事件缓冲区管理器。
 * <p>
 * 管理每个对话的 SseEventBuffer 实例，定期清理过期 buffer。
 */
@Slf4j
@Component
public class SseEventBufferManager {

    /** conversationId → SseEventBuffer */
    private final ConcurrentHashMap<String, SseEventBuffer> buffers = new ConcurrentHashMap<>();

    /** conversationId → resumeToken (UUID) */
    private final ConcurrentHashMap<String, String> resumeTokens = new ConcurrentHashMap<>();

    /**
     * 获取或创建对话的事件缓冲区。
     */
    public SseEventBuffer getOrCreate(String conversationId) {
        return buffers.computeIfAbsent(conversationId, k -> {
            log.debug("创建 SseEventBuffer: cid={}", k);
            return new SseEventBuffer();
        });
    }

    /**
     * 获取对话的事件缓冲区（不存在时返回 null）。
     */
    public SseEventBuffer getIfPresent(String conversationId) {
        return buffers.get(conversationId);
    }

    /**
     * 生成并存储 resumeToken。
     *
     * @return 生成的 UUID token
     */
    public String generateResumeToken(String conversationId) {
        String token = UUID.randomUUID().toString();
        resumeTokens.put(conversationId, token);
        return token;
    }

    /**
     * 设置 resumeToken。
     */
    public void setResumeToken(String conversationId, String token) {
        resumeTokens.put(conversationId, token);
    }

    /**
     * 验证 resumeToken 是否匹配。
     */
    public boolean validateResumeToken(String conversationId, String token) {
        String expected = resumeTokens.get(conversationId);
        return expected != null && expected.equals(token);
    }

    /**
     * 移除对话的事件缓冲区。
     */
    public void remove(String conversationId) {
        SseEventBuffer buf = buffers.remove(conversationId);
        if (buf != null) {
            buf.invalidate();
        }
        resumeTokens.remove(conversationId);
        log.debug("SseEventBuffer 已移除: cid={}", conversationId);
    }

    /**
     * 每 10s 清理一次已过期且无效的 buffer。
     */
    @Scheduled(fixedDelay = 10_000)
    public void cleanupExpired() {
        buffers.forEach((cid, buf) -> {
            if (!buf.isValid()) {
                log.debug("清理过期 SseEventBuffer: cid={}", cid);
                buffers.remove(cid);
                resumeTokens.remove(cid);
            }
        });
    }
}
