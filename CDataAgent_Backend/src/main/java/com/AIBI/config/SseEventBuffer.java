package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 事件环状缓冲区。
 * <p>
 * 每个对话一个实例，在流运行期间缓存事件。
 * 客户端断连后 30s 内可通过 resumeSeq 回放，超期自动失效。
 * <p>
 * 线程安全：ConcurrentLinkedDeque + AtomicLong。
 */
@Slf4j
@Component
public class SseEventBuffer {

    /** 最大缓存事件数 */
    private final int maxEvents;

    /** 最大缓存字节数 */
    private final long maxBytes;

    /** 环状队列 */
    private final ConcurrentLinkedDeque<BufferedEvent> buffer = new ConcurrentLinkedDeque<>();

    /** 当前字节数 */
    private final AtomicLong currentBytes = new AtomicLong(0);

    /** 最后写入的序号 */
    private final AtomicLong lastSeq = new AtomicLong(-1);

    /** 关联的 runId（请求级运行标识，用于防止跨运行事件串扰） */
    private volatile String runId;

    /** 流是否已结束 */
    private volatile boolean streamEnded = false;

    /** 过期时间点（流结束后设置） */
    private volatile Instant expireAt = null;

    /** 是否为有效缓冲区（流进行中或未过期） */
    private volatile boolean valid = true;

    public SseEventBuffer() {
        this(500, 2 * 1024 * 1024L);
    }

    public SseEventBuffer(int maxEvents, long maxBytes) {
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
    }

    /**
     * 设置关联的 runId。
     */
    public void setRunId(String runId) {
        this.runId = runId;
    }

    /**
     * 获取关联的 runId。
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 追加一条事件到缓冲区。
     *
     * @param seq    事件序号
     * @param type   事件类型（message / chart / status / complete 等）
     * @param data   事件数据
     */
    public void append(long seq, String type, String data) {
        if (streamEnded) return;

        BufferedEvent event = new BufferedEvent(seq, type, data);
        long eventBytes = event.estimatedBytes();
        buffer.addLast(event);
        lastSeq.set(seq);
        currentBytes.addAndGet(eventBytes);

        // 淘汰：超出最大事件数
        while (buffer.size() > maxEvents) {
            BufferedEvent removed = buffer.pollFirst();
            if (removed != null) {
                currentBytes.addAndGet(-removed.estimatedBytes());
            }
        }

        // 淘汰：超出最大字节数
        while (currentBytes.get() > maxBytes) {
            BufferedEvent removed = buffer.pollFirst();
            if (removed != null) {
                currentBytes.addAndGet(-removed.estimatedBytes());
            }
        }
    }

    /**
     * 从指定序号开始获取回放事件列表。
     * 如果该序号已不在缓冲区中（被淘汰），返回最旧的事件。
     *
     * @param seq 客户端已接收到的最大序号
     * @return 需回放的事件列表（可能为空）
     */
    public List<BufferedEvent> getFromSeq(long seq) {
        if (!valid || expireAt != null && Instant.now().isAfter(expireAt)) {
            return List.of();
        }

        List<BufferedEvent> result = new ArrayList<>();
        for (BufferedEvent event : buffer) {
            if (event.seq() > seq) {
                result.add(event);
            }
        }
        // 如果 seq 完全不在缓冲区（太旧），返回全部
        if (!buffer.isEmpty() && seq < buffer.peekFirst().seq()) {
            result = new ArrayList<>(buffer);
        }
        return result;
    }

    /** 当前最大序号 */
    public long lastSequence() {
        return lastSeq.get();
    }

    /**
     * 通知缓冲区流已结束。
     * 设置 30s 后过期，以便重连客户端有时间读取。
     */
    public void markStreamEnd() {
        this.streamEnded = true;
        this.expireAt = Instant.now().plus(30, ChronoUnit.SECONDS);
        log.debug("事件缓冲区标记流结束，30s后过期: seq={}, size={}", lastSeq.get(), buffer.size());
    }

    /** 缓冲区是否仍有效（流进行中，或流结束但未超期） */
    public boolean isValid() {
        if (!valid) return false;
        if (expireAt != null && Instant.now().isAfter(expireAt)) {
            valid = false;
            buffer.clear();
            return false;
        }
        return true;
    }

    /** 流是否仍在进行中（未结束） */
    public boolean isStreamActive() {
        return !streamEnded;
    }

    /** 强制失效并清理 */
    public void invalidate() {
        valid = false;
        buffer.clear();
        currentBytes.set(0);
        expireAt = Instant.now();
        log.debug("事件缓冲区已强制失效");
    }

    // ── 内部记录类型 ──

    public record BufferedEvent(long seq, String type, String data) {
        public long estimatedBytes() {
            // 粗略估算：type + data 的字节数 + 固定开销
            return (type != null ? type.length() : 0)
                    + (data != null ? data.length() : 0)
                    + 64; // 对象头 + seq long
        }
    }
}
