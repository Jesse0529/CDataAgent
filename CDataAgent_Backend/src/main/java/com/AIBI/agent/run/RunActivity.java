package com.AIBI.agent.run;

/**
 * 面向用户的运行活动，不包含工具名、参数和内部数据。
 */
public record RunActivity(
        String id,
        String stage,
        String label,
        State state
) {
    public enum State {
        RUNNING, SUCCEEDED, FAILED
    }
}
