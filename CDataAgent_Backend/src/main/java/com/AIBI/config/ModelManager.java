package com.AIBI.config;

import com.AIBI.common.ErrorCode;
import com.AIBI.exception.BusinessException;
import com.AIBI.manager.TokenLedger;
import com.AIBI.utils.AesGcmUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型管理器——用户必须通过 API 配置模型后使用。
 * <p>
 * 未配置时抛出异常引导配置，无默认兜底模型。
 */
@Slf4j
@Primary
@Component
public class ModelManager implements ChatModel {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ToolCallingManager toolCallingManager;

    @Autowired
    private RetryTemplate retryTemplate;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private TokenLedger tokenLedger;

    @Value("${model.encryption.key}")
    private String encryptionKey;

    /** 当前请求的对话 ID（Agent 框架调用 ChatModel 时的线程安全上下文） */
    private final AtomicLong currentConversationId = new AtomicLong(0L);

    public void setCurrentConversationId(Long cid) {
        currentConversationId.set(cid);
    }

    public void clearCurrentConversationId() {
        currentConversationId.set(0L);
    }

    @PostConstruct
    public void initEncryption() {
        AesGcmUtil.init(encryptionKey);
    }

    /** 模型缓存（仅手动失效，对比 Redis 配置决定是否重建） */
    private final Cache<Long, ModelCacheEntry> modelCache = Caffeine.newBuilder()
            .maximumSize(1)
            .build();

    private static final String REDIS_KEY = "model:config:default";
    private static final Long CACHE_KEY = 1L;

    /** 缓存条目：模型实例 + 创建时的 Redis 配置 */
    private record ModelCacheEntry(ChatModel model, String configJson) {}

    // ─── ChatModel 接口 ──────────────────────────

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = resolveModel().call(prompt);
        recordUsage(response);
        return response;
    }

    @Override
    public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
        return resolveModel().stream(prompt)
                .doOnNext(this::recordUsage);
    }

    // ─── Token 记录 ────────────────────────────────

    /**
     * 从 ChatResponse 提取精确 token 用量并记录到 TokenLedger。
     * 当 currentConversationId 未设置（非对话场景的模型调用）时静默跳过。
     */
    private void recordUsage(ChatResponse response) {
        long cid = currentConversationId.get();
        if (cid <= 0L) return;

        Usage usage = response.getMetadata().getUsage();
        if (usage == null) return;

        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        if (promptTokens == null || completionTokens == null) return;

        try {
            tokenLedger.recordRoundModelCall(cid, promptTokens, completionTokens);
        } catch (Exception e) {
            log.warn("ModelManager token 记录失败: cid={}", cid, e);
        }
    }

    // ─── 模型解析 ────────────────────────────────

    private ChatModel resolveModel() {
        // 从 Redis 读取当前配置
        RBucket<String> bucket = redissonClient.getBucket(REDIS_KEY);
        String configJson = bucket.get();
        if (configJson == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "请先配置模型后再使用");
        }

        // 检查缓存是否存在且配置一致
        ModelCacheEntry entry = modelCache.getIfPresent(CACHE_KEY);
        if (entry != null && configJson.equals(entry.configJson)) {
            log.debug("resolveModel: 缓存命中，配置未变更");
            return entry.model;
        }

        // 缓存不存在或配置已变更 → 重建模型
        try {
            JSONObject cfg = JSON.parseObject(configJson);
            log.info("resolveModel: 配置已变更或首次加载, provider={}, model={}",
                    cfg.getString("provider"), cfg.getString("modelName"));
            ChatModel userModel = buildChatModel(cfg);
            modelCache.put(CACHE_KEY, new ModelCacheEntry(userModel, configJson));
            log.info("自定义模型已创建: provider={}, model={}",
                    cfg.getString("provider"), cfg.getString("modelName"));
            return userModel;
        } catch (Exception e) {
            log.error("创建自定义模型失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "模型配置无效: " + e.getMessage());
        }
    }

    // ─── 模型构建 ────────────────────────────────

    @SuppressWarnings("deprecation")
    private ChatModel buildChatModel(JSONObject cfg) {
        String provider = cfg.getString("provider");
        String apiKey = AesGcmUtil.decrypt(cfg.getString("apiKey"));
        String modelName = cfg.getString("modelName");
        String baseUrl = cfg.getString("baseUrl");
        Integer maxTokens = cfg.getInteger("maxTokens");
        Double temperature = cfg.getDouble("temperature");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        return switch (provider.toUpperCase()) {
            case "DEEPSEEK" -> buildDeepSeek(apiKey, modelName, baseUrl, maxTokens, temperature);
            case "CUSTOM" -> buildOpenAi(apiKey, modelName, baseUrl, maxTokens, temperature);
            default -> throw new IllegalArgumentException("不支持的提供商: " + provider);
        };
    }

    @SuppressWarnings("deprecation")
    private ChatModel buildDeepSeek(String apiKey, String modelName, String baseUrl,
                                     Integer maxTokens, Double temperature) {
        DeepSeekApi api = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.deepseek.com")
                .restClientBuilder(org.springframework.web.client.RestClient.builder()
                    .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build())))
                .webClientBuilder(org.springframework.web.reactive.function.client.WebClient.builder())
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(modelName)
                        .maxTokens(maxTokens != null ? maxTokens : 16384)
                        .temperature(temperature != null ? temperature : 0.7)
                        .build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }

    @SuppressWarnings("deprecation")
    private ChatModel buildOpenAi(String apiKey, String modelName, String baseUrl,
                                   Integer maxTokens, Double temperature) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.openai.com")
                .restClientBuilder(org.springframework.web.client.RestClient.builder()
                    .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build())))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .maxTokens(maxTokens != null ? maxTokens : 16384)
                        .temperature(temperature != null ? temperature : 0.7)
                        .build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry)
                .build();
    }

    /** 清除缓存（配置变更时调用）。 */
    public void evictDefaultCache() {
        modelCache.invalidate(CACHE_KEY);
    }
}
