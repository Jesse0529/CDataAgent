package com.AIBI.agent.run;

/**
 * RunContext 静态持有者。
 * <p>
 * 在 AgentServiceImpl.executePipeline 开始时设置，doFinally 中清除。
 * 工具类通过 {@link #require()} 获取当前请求的 RunContext。
 * <p>
 * 使用 static volatile 字段而非 ThreadLocal：
 * Spring AI Alibaba Agent Framework 的 LLM 调用和工具回调在 Reactor/Netty 线程上执行，
 * 与设置 RunContext 的 Tomcat 线程不同，ThreadLocal 跨线程不可见。
 * 当前系统的 Redisson 锁保证了同一对话的串行执行，static 字段是安全的。
 */
public class RunContextHolder {

    private static volatile RunContext current;

    private RunContextHolder() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前 RunContext。
     */
    public static void set(RunContext context) {
        current = context;
    }

    /**
     * 获取当前 RunContext。
     *
     * @return 当前 RunContext，如果未设置则返回 null
     */
    public static RunContext get() {
        return current;
    }

    /**
     * 获取当前 RunContext，如果未设置则抛出异常。
     *
     * @throws IllegalStateException 如果 RunContext 未设置
     */
    public static RunContext require() {
        RunContext ctx = current;
        if (ctx == null) {
            throw new IllegalStateException("RunContext 未设置 — 工具方法只能在 Agent 执行期间调用");
        }
        return ctx;
    }

    /**
     * 清除当前 RunContext（防止残留到下一次请求）。
     */
    public static void clear() {
        current = null;
    }
}
