package com.AIBI.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 将同一流式模型请求中重复上报的累计 usage 转为增量。
 *
 * <p>部分 OpenAI 兼容实现会先发送 0/0 的占位 usage，并在最后一个分片发送完整用量；
 * 不能用“首个 responseId 已见”来去重，否则会丢失最终用量。</p>
 */
final class StreamingUsageAccumulator {

    private static final String FALLBACK_STREAM_KEY = "__single_stream_response__";

    private final Map<String, UsageSnapshot> snapshots = new HashMap<>();

    /**
     * 接收一个累计 usage 快照，返回本次应新增记录的 token 差量。
     */
    Optional<UsageDelta> accept(String responseId, Integer promptTokens, Integer completionTokens) {
        if (promptTokens == null || completionTokens == null
                || promptTokens < 0 || completionTokens < 0) {
            return Optional.empty();
        }

        // DeepSeek 的非最终流式分片常使用 0/0 占位；它不代表一次真实计费。
        if (promptTokens == 0 && completionTokens == 0) {
            return Optional.empty();
        }

        String key = responseId == null || responseId.isBlank()
                ? FALLBACK_STREAM_KEY : responseId;
        UsageSnapshot previous = snapshots.get(key);
        if (previous == null) {
            snapshots.put(key, new UsageSnapshot(promptTokens, completionTokens));
            return Optional.of(new UsageDelta(promptTokens, completionTokens));
        }

        // usage 按累计快照处理，只记录增长部分，避免同一调用被多个分片重复计费。
        int inputDelta = Math.max(0, promptTokens - previous.inputTokens());
        int outputDelta = Math.max(0, completionTokens - previous.outputTokens());
        snapshots.put(key, new UsageSnapshot(
                Math.max(previous.inputTokens(), promptTokens),
                Math.max(previous.outputTokens(), completionTokens)));

        return inputDelta == 0 && outputDelta == 0
                ? Optional.empty()
                : Optional.of(new UsageDelta(inputDelta, outputDelta));
    }

    record UsageDelta(int inputTokens, int outputTokens) {
    }

    private record UsageSnapshot(int inputTokens, int outputTokens) {
    }
}
