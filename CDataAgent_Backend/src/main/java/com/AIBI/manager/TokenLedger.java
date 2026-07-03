package com.AIBI.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Token 消耗账本 — 基于 API 返回的精确值，不做估算。
 * <p>
 * 每次模型调用后，由 AgentServiceImpl 从 {@code NodeOutput.tokenUsage()} 获取精确值并记录。
 * 用于：对话级 Token 预算判断、tokenUsage 字段回填、成本监控。
 * <p>
 * Redis 结构（Hash）:
 *   key: "conv:ledger:{conversationId}"
 *   fields:
 *     lastInputTokens  最近一次模型调用的输入 token（精确值，反映当前消息列表大小）
 *     lastOutputTokens 最近一次模型调用的输出 token
 *     totalInputTokens 累计输入 token（精确，可用于成本核算）
 *     totalOutputTokens 累计输出 token
 *     modelName        当前模型
 *   TTL: 3 天（与 Redis Checkpoint 对齐）
 */
@Slf4j
@Component
public class TokenLedger {

    private static final String KEY_PREFIX = "conv:ledger:";
    private static final Duration TTL = Duration.ofDays(3);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 记录一次模型调用的精确 token 消耗。
     * 由 Agent 流处理中的 doOnNext 调用。
     */
    public void recordModelCall(Long conversationId, int inputTokens, int outputTokens) {
        String key = KEY_PREFIX + conversationId;
        try {
            stringRedisTemplate.opsForHash().put(key, "lastInputTokens", String.valueOf(inputTokens));
            stringRedisTemplate.opsForHash().put(key, "lastOutputTokens", String.valueOf(outputTokens));
            stringRedisTemplate.opsForHash().increment(key, "totalInputTokens", inputTokens);
            stringRedisTemplate.opsForHash().increment(key, "totalOutputTokens", outputTokens);
            stringRedisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("TokenLedger 记录失败: cid={}, input={}, out={}",
                    conversationId, inputTokens, outputTokens, e);
        }
    }

    /**
     * 获取当前框架消息列表的 Token 大小。
     * = 最近一次模型调用的 inputTokens，精确反映 Checkpoint 消息列表大小。
     * 为 0 表示无 Checkpoint（新对话）或尚未记录。
     */
    public int getCurrentMessageListTokens(Long conversationId) {
        String val = (String) stringRedisTemplate.opsForHash()
                .get(KEY_PREFIX + conversationId, "lastInputTokens");
        return val == null ? 0 : Integer.parseInt(val);
    }

    /**
     * 获取本轮累计输入 token 数。
     */
    public long getTotalInputTokens(Long conversationId) {
        String val = (String) stringRedisTemplate.opsForHash()
                .get(KEY_PREFIX + conversationId, "totalInputTokens");
        return val == null ? 0 : Long.parseLong(val);
    }

    /**
     * 获取本轮累计输出 token 数。
     */
    public long getTotalOutputTokens(Long conversationId) {
        String val = (String) stringRedisTemplate.opsForHash()
                .get(KEY_PREFIX + conversationId, "totalOutputTokens");
        return val == null ? 0 : Long.parseLong(val);
    }

    /**
     * 消费本轮累计 token（原子读取 + 重置）。
     * 用于 doOnComplete 中回填 tokenUsage 字段后清零。
     */
    public Optional<RoundTokenUsage> consumeRoundUsage(Long conversationId) {
        String key = KEY_PREFIX + conversationId;

        // 读取本轮新增
        long inputDelta = getAndClearField(key, "roundInputTokens");
        long outputDelta = getAndClearField(key, "roundOutputTokens");

        if (inputDelta == 0 && outputDelta == 0) {
            // 可能没通过 recordModelCall 记录（未配置或早期版本），回退读取累计值
            inputDelta = getTotalInputTokens(conversationId);
            outputDelta = getTotalOutputTokens(conversationId);
            // 不回退清零
            return Optional.of(new RoundTokenUsage(inputDelta, outputDelta));
        }

        return Optional.of(new RoundTokenUsage(inputDelta, outputDelta));
    }

    /**
     * 记录本轮单次模型调用（同时计入累计和本轮）。
     */
    public void recordRoundModelCall(Long conversationId, int inputTokens, int outputTokens) {
        String key = KEY_PREFIX + conversationId;
        try {
            // 累计（跨轮）
            stringRedisTemplate.opsForHash().put(key, "lastInputTokens", String.valueOf(inputTokens));
            stringRedisTemplate.opsForHash().put(key, "lastOutputTokens", String.valueOf(outputTokens));
            stringRedisTemplate.opsForHash().increment(key, "totalInputTokens", inputTokens);
            stringRedisTemplate.opsForHash().increment(key, "totalOutputTokens", outputTokens);
            // 本轮（单轮内累计，消费后清零）
            stringRedisTemplate.opsForHash().increment(key, "roundInputTokens", inputTokens);
            stringRedisTemplate.opsForHash().increment(key, "roundOutputTokens", outputTokens);
            stringRedisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("TokenLedger 记录失败: cid={}, input={}, out={}",
                    conversationId, inputTokens, outputTokens, e);
        }
    }

    /**
     * 重置指定对话的 Token 数据（对话重置时调用）。
     */
    public void reset(Long conversationId) {
        try {
            stringRedisTemplate.delete(KEY_PREFIX + conversationId);
            log.debug("TokenLedger 已重置: cid={}", conversationId);
        } catch (Exception e) {
            log.warn("TokenLedger 重置失败: cid={}", conversationId, e);
        }
    }

    // ─── 内部方法 ─────────────────────────────────────────────

    private long getAndClearField(String key, String field) {
        try {
            String val = (String) stringRedisTemplate.opsForHash().get(key, field);
            if (val == null) return 0;
            stringRedisTemplate.opsForHash().delete(key, field);
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0;
        }
    }

    // ─── 内部类 ──────────────────────────────────────────────

    /**
     * 单轮对话的 Token 消耗。
     */
    public record RoundTokenUsage(long inputTokens, long outputTokens) {
        public long total() { return inputTokens + outputTokens; }
    }
}
