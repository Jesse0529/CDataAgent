package com.AIBI.config;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * RedisSaver 装饰器 — 每次写入 checkpoint 后自动续期 TTL。
 * <p>
 * 解决框架原生 RedisSaver 不设过期时间、僵尸数据堆积的问题。
 * 活跃对话每次 put() 都会续期，闲置超过 TTL 后 Redis 自动清理。
 * <p>
 * 使用 {@link BaseCheckpointSaver} 接口，不依赖 RedisSaver 具体实现：
 * <pre>{@code
 * TtlRedisSaver wrapper = new TtlRedisSaver(redisSaver, redissonClient, Duration.ofDays(3));
 * }</pre>
 */
public class TtlRedisSaver implements BaseCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(TtlRedisSaver.class);

    /** Redis key 前缀，与框架 RedisSaver 中的定义保持一致 */
    private static final String CHECKPOINT_PREFIX = "graph:checkpoint:content:";
    private static final String THREAD_META_PREFIX = "graph:thread:meta:";
    private static final String THREAD_REVERSE_PREFIX = "graph:thread:reverse:";

    private final BaseCheckpointSaver delegate;
    private final RedissonClient redisson;
    private final Duration ttl;

    public TtlRedisSaver(BaseCheckpointSaver delegate, RedissonClient redisson, Duration ttl) {
        this.delegate = delegate;
        this.redisson = redisson;
        this.ttl = ttl;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        return delegate.list(config);
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        return delegate.get(config);
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        RunnableConfig result = delegate.put(config, checkpoint);
        renewTtl(config);
        return result;
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        return delegate.release(config);
    }

    /**
     * 物理删除指定 threadName 在 Redis 中的所有 checkpoint 相关 key。
     * <p>
     * 与 {@link #release(RunnableConfig)} 不同，此方法直接删除（而非标记释放），
     * 用于对话重置场景，使 Agent 的对话记忆彻底清除。
     *
     * @param threadName 对话 ID 的字符串形式（即 conversationId.toString()）
     */
    public void deleteCheckpoints(String threadName) {
        try {
            RMap<String, String> meta = redisson.getMap(THREAD_META_PREFIX + threadName);
            String threadId = meta.get("thread_id");

            if (threadId != null) {
                // 1. checkpoint 数据 bucket
                redisson.<String>getBucket(CHECKPOINT_PREFIX + threadId).delete();
                // 2. 反向索引 map
                redisson.<String, String>getMap(THREAD_REVERSE_PREFIX + threadId).delete();
                log.debug("Redis检查点已删除: threadId={}", threadId);
            }
            // 3. thread 元数据 map（即使 threadId 为 null 也删除）
            meta.delete();
            log.info("Redis检查点已删除: threadName={}", threadName);
        } catch (Exception e) {
            log.warn("Redis检查点删除失败: threadName={}", threadName, e);
        }
    }

    /**
     * 续期当前 thread 关联的 3 个 Redis key。
     * 这些 key 由框架 RedisSaver 在内部创建，这里在每次 put() 后统一续期。
     */
    private void renewTtl(RunnableConfig config) {
        String threadName = config.threadId().orElse("$default");
        try {
            RMap<String, String> meta = redisson.getMap(THREAD_META_PREFIX + threadName);
            String threadId = meta.get("thread_id");
            if (threadId == null) {
                return;
            }

            // 1. checkpoint 数据 bucket
            redisson.<String>getBucket(CHECKPOINT_PREFIX + threadId).expire(ttl);
            // 2. thread 元数据 map
            meta.expire(ttl);
            // 3. 反向索引 map
            redisson.<String, String>getMap(THREAD_REVERSE_PREFIX + threadId).expire(ttl);
        } catch (Exception e) {
            // TTL 续期失败不影响 Agent 核心流程，仅记日志
            log.warn("TTL续期失败: threadName={}", threadName, e);
        }
    }
}
