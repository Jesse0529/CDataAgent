package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final int TERMINAL_STATE_SECONDS = 300;

    /** 对话 → Sinks.Many（热点事件源） */
    private final ConcurrentHashMap<String, Sinks.Many<Map<String, String>>> sinks = new ConcurrentHashMap<>();

    /** 对话 → 取消定时器 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    /** 对话 → 是否已被取消 */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();

    /** 对话 → 已连接的 SSE 客户端数 */
    private final ConcurrentHashMap<String, AtomicLong> clientCounts = new ConcurrentHashMap<>();

    /** 运行状态仅由 Agent 执行流的终态更新，不能由 SSE 断连更新。 */
    private final ConcurrentHashMap<String, RunState> runStates = new ConcurrentHashMap<>();

    /** 已超过实时续播窗口，但 Agent 仍在后台安全执行。 */
    private final ConcurrentHashMap<String, Boolean> resumeExpired = new ConcurrentHashMap<>();

    /** 运行恢复凭据在终态后短暂保留，供前端判定结果。 */
    private final ConcurrentHashMap<String, String> resumeTokens = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ScheduledFuture<?>> terminalCleanups = new ConcurrentHashMap<>();

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
    public Sinks.Many<Map<String, String>> register(String cid, Sinks.Many<Map<String, String>> sink,
                                                      SseEventBuffer buffer, String resumeToken) {
        cancelled.put(cid, new AtomicBoolean(false));
        clientCounts.put(cid, new AtomicLong(0));
        runStates.put(cid, RunState.RUNNING);
        resumeExpired.remove(cid);
        resumeTokens.put(cid, resumeToken);
        sinks.put(cid, sink);
        buffers.put(cid, buffer);
        log.debug("SSE流已注册");
        return sink;
    }

    /**
     * 获取对话的 Sinks.Many，用于重连时接回。
     */
    public Sinks.Many<Map<String, String>> getSink(String cid) {
        return sinks.get(cid);
    }

    /** 客户端建立订阅。返回 false 表示运行已经结束或被取消。 */
    public boolean attachClient(String cid) {
        AtomicBoolean flag = cancelled.get(cid);
        AtomicLong clients = clientCounts.get(cid);
        if (flag == null || clients == null || flag.get()
                || runStates.get(cid) != RunState.RUNNING
                || Boolean.TRUE.equals(resumeExpired.get(cid))) return false;

        clients.incrementAndGet();
        ScheduledFuture<?> timer = timers.remove(cid);
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }
        return true;
    }

    /**
     * 客户端断连时调用。仅最后一个订阅者离开时启动 30 秒恢复窗口。
     */
    public void detachClient(String cid) {
        AtomicBoolean flag = cancelled.get(cid);
        AtomicLong clients = clientCounts.get(cid);
        if (flag == null || clients == null || flag.get()) return;

        long remaining = clients.updateAndGet(value -> Math.max(0, value - 1));
        if (remaining > 0) return;

        ScheduledFuture<?> existing = timers.get(cid);
        if (existing != null && !existing.isDone()) return; // 已有活跃定时器

        ScheduledFuture<?> timer = scheduler.schedule(() -> expireResumeWindow(cid), GRACE_SECONDS, TimeUnit.SECONDS);

        timers.put(cid, timer);
        log.info("SSE断连，启动{}s实时恢复窗口", GRACE_SECONDS);
    }

    /**
     * 注册冷 Flux 的 Disposable，供 forceCancel 时取消以释放锁。
     */
    public void setColdSubscription(String cid, Disposable disposable) {
        if (sinks.containsKey(cid)) {
            coldSubscriptions.put(cid, disposable);
        } else {
            disposable.dispose();
        }
    }

    /** Agent 执行真正结束后调用；与浏览器断连无关。 */
    public void completeRun(String cid, RunState state) {
        if (state == RunState.RUNNING) {
            throw new IllegalArgumentException("终态不能为 RUNNING");
        }
        runStates.put(cid, state);
        ScheduledFuture<?> timer = timers.remove(cid);
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }
        coldSubscriptions.remove(cid);
        sinks.remove(cid);
        buffers.remove(cid);
        clientCounts.remove(cid);
        cancelled.remove(cid);
        resumeExpired.remove(cid);
        scheduleTerminalCleanup(cid);
        log.debug("Agent运行结束: state={}", state);
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

        Sinks.Many<Map<String, String>> sink = sinks.get(cid);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        ScheduledFuture<?> timer = timers.remove(cid);
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }

        SseEventBuffer buf = buffers.get(cid);
        if (buf != null) {
            buf.markStreamEnd();
        }
        completeRun(cid, RunState.CANCELLED);

        log.info("SSE流已强制取消");
    }

    public boolean validateResumeToken(String cid, String token) {
        String expected = resumeTokens.get(cid);
        return expected != null && expected.equals(token);
    }

    public RunSnapshot snapshot(String cid) {
        RunState state = runStates.get(cid);
        if (state == null) return null;
        return new RunSnapshot(state, state == RunState.RUNNING
                && !Boolean.TRUE.equals(resumeExpired.get(cid)) && sinks.containsKey(cid));
    }

    private void expireResumeWindow(String cid) {
        AtomicLong clients = clientCounts.get(cid);
        if (clients == null || clients.get() > 0 || runStates.get(cid) != RunState.RUNNING) return;
        resumeExpired.put(cid, true);
        log.info("SSE实时恢复窗口结束，Agent继续后台执行");
    }

    private void scheduleTerminalCleanup(String cid) {
        ScheduledFuture<?> old = terminalCleanups.remove(cid);
        if (old != null && !old.isDone()) old.cancel(false);
        ScheduledFuture<?> cleanup = scheduler.schedule(() -> {
            runStates.remove(cid);
            resumeTokens.remove(cid);
            resumeExpired.remove(cid);
            terminalCleanups.remove(cid);
        }, TERMINAL_STATE_SECONDS, TimeUnit.SECONDS);
        terminalCleanups.put(cid, cleanup);
    }

    public enum RunState {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    public record RunSnapshot(RunState state, boolean resumable) {}

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
