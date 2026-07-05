package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 优雅断开管理器。
 * <p>
 * 每个对话一个热点 Sinks.Many，客户端断连后启动 30s 优雅窗格。
 * 窗格内可重连续播；超期则强制终止并清理。
 */
@Slf4j
@Component
public class DisconnectGraceManager {

    /** 优雅窗格时长（秒） */
    private static final int GRACE_SECONDS = 30;

    /** 对话 → Sinks.Many（热点事件源） */
    private final ConcurrentHashMap<String, Sinks.Many<Map<String, String>>> sinks = new ConcurrentHashMap<>();

    /** 对话 → 取消定时器 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    /** 对话 → 是否已被取消 */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();

    /** 对话 → SseEventBuffer */
    private final ConcurrentHashMap<String, SseEventBuffer> buffers = new ConcurrentHashMap<>();

    /** 对话 → 冷 Flux Disposable（需在 forceCancel 时取消以释放锁） */
    private final ConcurrentHashMap<String, reactor.core.Disposable> coldSubscriptions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "grace-cleanup");
        t.setDaemon(true);
        return t;
    });

    /**
     * 注册一个活跃流到管理器中。
     *
     * @param cid    对话 ID
     * @param sink   热点 Sinks.Many
     * @param buffer SseEventBuffer 实例
     */
    public Sinks.Many<Map<String, String>> register(String cid, Sinks.Many<Map<String, String>> sink, SseEventBuffer buffer) {
        cancelled.put(cid, new AtomicBoolean(false));
        sinks.put(cid, sink);
        buffers.put(cid, buffer);
        log.debug("GraceManager 注册流: cid={}", cid);
        return sink;
    }

    /**
     * 获取对话的 Sinks.Many，用于重连时接回。
     */
    public Sinks.Many<Map<String, String>> getSink(String cid) {
        return sinks.get(cid);
    }

    /**
     * 客户端断连时调用：启动 30s 优雅窗格。
     * 到期后取消 Sinks，触发下游清理。
     */
    public void onDisconnect(String cid) {
        AtomicBoolean flag = cancelled.get(cid);
        if (flag != null && flag.get()) return; // 已取消

        ScheduledFuture<?> existing = timers.get(cid);
        if (existing != null && !existing.isDone()) return; // 已有活跃定时器

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            log.warn("优雅窗格到期，强制终止流: cid={}", cid);
            forceCancel(cid);
        }, GRACE_SECONDS, TimeUnit.SECONDS);

        timers.put(cid, timer);
        log.info("SSE 断连，启动 {}s 优雅窗格: cid={}", GRACE_SECONDS, cid);
    }

    /**
     * 客户端重连时调用：取消 30s 定时器，返回 Sinks.Many 的热点 Flux。
     *
     * @return 热点 Flux（可 skip），若已超期返回 null
     */
    public Flux<Map<String, String>> onReconnect(String cid) {
        AtomicBoolean flag = cancelled.get(cid);
        if (flag != null && flag.get()) {
            log.warn("重连失败，流已取消: cid={}", cid);
            return null;
        }
        if (flag == null) {
            log.warn("重连失败，无活跃流: cid={}", cid);
            return null;
        }

        // 取消定时器
        ScheduledFuture<?> timer = timers.remove(cid);
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }

        Sinks.Many<Map<String, String>> sink = sinks.get(cid);
        if (sink == null) return null;

        log.info("SSE 重连成功，接回活跃流: cid={}", cid);
        return sink.asFlux();
    }

    /**
     * 注册冷 Flux 的 Disposable，供 forceCancel 时取消以释放锁。
     */
    public void setColdSubscription(String cid, Disposable disposable) {
        coldSubscriptions.put(cid, disposable);
    }

    /**
     * 强制取消流：发出完成信号 + 取消冷 Flux（释放锁）+ 清理资源。
     */
    public void forceCancel(String cid) {
        AtomicBoolean flag = cancelled.get(cid);
        if (flag != null) flag.set(true);

        // 取消冷 Flux → doFinally(CANCEL) 触发 → 锁释放
        Disposable coldSub = coldSubscriptions.remove(cid);
        if (coldSub != null && !coldSub.isDisposed()) {
            coldSub.dispose();
        }

        Sinks.Many<Map<String, String>> sink = sinks.remove(cid);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        ScheduledFuture<?> timer = timers.remove(cid);
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }

        SseEventBuffer buf = buffers.remove(cid);
        if (buf != null) {
            buf.markStreamEnd();
        }

        log.info("SSE 流已强制取消: cid={}", cid);
    }

    /**
     * 检查对话的流是否已被取消。
     */
    public boolean isCancelled(String cid) {
        AtomicBoolean flag = cancelled.get(cid);
        return flag != null && flag.get();
    }

    /**
     * 移除对话的所有状态（用于对话重置等场景）。
     */
    public void remove(String cid) {
        forceCancel(cid);
    }
}
