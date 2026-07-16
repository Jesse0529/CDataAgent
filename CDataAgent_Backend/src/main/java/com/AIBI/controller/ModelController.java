package com.AIBI.controller;

import com.AIBI.common.BaseResponse;
import com.AIBI.common.ErrorCode;
import com.AIBI.common.ResultUtils;
import com.AIBI.config.ModelManager;
import com.AIBI.config.ModelProperties;
import com.AIBI.exception.BusinessException;
import com.AIBI.utils.AesGcmUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户模型配置接口。
 * <p>
 * 自部署单用户模式，配置存储在 Redis（key: model:config:default），即时生效无需重启。
 * 删除配置后自动回退到系统默认模型。
 */
@RestController
@RequestMapping("/apis/model")
@Slf4j
public class ModelController {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ModelProperties modelProps;

    @Autowired
    private ModelManager modelManager;

    private static final String REDIS_KEY = "model:config:default";
    private static final long CONFIG_TTL_DAYS = 30;
    private static final Set<String> VALID_PROVIDERS = Set.of("DEEPSEEK", "CUSTOM");

    private static final Map<String, List<String>> PROVIDER_MODELS = Map.of(
            "DEEPSEEK", List.of("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-r1"),
            "CUSTOM",   List.of()
    );

    /** 获取指定提供商的可选模型列表 */
    @GetMapping("/models")
    public BaseResponse<Map<String, Object>> getModels(@RequestParam String provider) {
        String key = provider.toUpperCase().trim();
        List<String> models = PROVIDER_MODELS.getOrDefault(key, List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", key);
        result.put("models", models);
        if ("CUSTOM".equals(key)) {
            result.put("note", "CUSTOM 模式需手动输入模型名称，如 gpt-4o-mini、qwen-plus 等");
        }
        return ResultUtils.success(result);
    }

    /** 获取当前模型配置 */

    /** 获取当前模型配置 */
    @GetMapping("/config")
    public BaseResponse<Map<String, Object>> getConfig() {
        RBucket<String> bucket = redissonClient.getBucket(REDIS_KEY);
        String json = bucket.get();

        Map<String, Object> result = new LinkedHashMap<>();
        if (json != null) {
            JSONObject cfg = JSON.parseObject(json);
            result.put("configured", true);
            result.put("provider", cfg.getString("provider"));
            result.put("modelName", cfg.getString("modelName"));
            result.put("baseUrl", cfg.getOrDefault("baseUrl", ""));
            // 解密后 mask，再返回
            String apiKeyPlain = AesGcmUtil.decrypt(cfg.getString("apiKey"));
            result.put("apiKeyMasked", maskApiKey(apiKeyPlain));
        } else {
            result.put("configured", false);
            result.put("provider", modelProps.getProvider());
            result.put("modelName", modelProps.getModelName());
            result.put("note", "使用系统默认模型配置");
        }

        return ResultUtils.success(result);
    }

    /** 保存模型配置到 Redis */
    @PostMapping("/config")
    public BaseResponse<Map<String, String>> saveConfig(@RequestBody Map<String, Object> body) {
        String provider = (String) body.get("provider");
        String apiKey = (String) body.get("apiKey");
        String modelName = (String) body.get("modelName");
        Object baseUrlRaw = body.getOrDefault("baseUrl", "");
        String baseUrl = baseUrlRaw != null ? baseUrlRaw.toString().trim() : "";

        // 必填校验
        if (provider == null || provider.isBlank())
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "provider 不能为空");
        if (modelName == null || modelName.isBlank())
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "modelName 不能为空");

        // provider 校验
        provider = provider.toUpperCase().trim();
        if (!VALID_PROVIDERS.contains(provider)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "不支持的 provider: " + provider + "（仅支持 DEEPSEEK/CUSTOM）");
        }

        // CUSTOM 模式 baseUrl 必填校验
        if ("CUSTOM".equals(provider) && baseUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "CUSTOM 模式下 baseUrl 不能为空（需填写兼容 OpenAI 协议的 API 地址）");
        }

        // baseUrl 格式校验（非空时）
        if (!baseUrl.isEmpty()) {
            validateBaseUrl(baseUrl);
        }

        // DEEPSEEK 模式下强制使用默认地址，避免从 CUSTOM 切回时残留旧 baseUrl
        if ("DEEPSEEK".equals(provider)) {
            baseUrl = "";
        }

        JSONObject cfg = new JSONObject();
        cfg.put("provider", provider);
        cfg.put("modelName", modelName.trim());
        cfg.put("baseUrl", baseUrl);

        // apiKey：传了新 key 则加密存储，未传则保留已有配置中的 key
        if (apiKey != null && !apiKey.isBlank()) {
            apiKey = apiKey.trim();
            if (apiKey.length() < 8) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "apiKey 格式无效（长度不足）");
            }
            cfg.put("apiKey", AesGcmUtil.encrypt(apiKey));
        } else {
            RBucket<String> existingBucket = redissonClient.getBucket(REDIS_KEY);
            String existingJson = existingBucket.get();
            if (existingJson != null) {
                String existingKey = JSON.parseObject(existingJson).getString("apiKey");
                if (existingKey != null) {
                    cfg.put("apiKey", existingKey);
                }
            }
        }

        RBucket<String> bucket = redissonClient.getBucket(REDIS_KEY);
        bucket.set(cfg.toJSONString(), CONFIG_TTL_DAYS, TimeUnit.DAYS);

        // 清除缓存，下次请求即时生效
        modelManager.evictDefaultCache();

        log.info("模型配置已保存: provider={}, model={}", provider, modelName);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("modelName", modelName);
        return ResultUtils.success(result);
    }

    /** 删除模型配置，回退到系统默认 */
    @DeleteMapping("/config")
    public BaseResponse<String> deleteConfig() {
        RBucket<String> bucket = redissonClient.getBucket(REDIS_KEY);
        bucket.delete();

        modelManager.evictDefaultCache();

        log.info("模型配置已删除，回退到默认");
        return ResultUtils.success("已恢复系统默认模型配置");
    }

    // ─── 校验 ──────────────────────────────────────

    /**
     * 校验 baseUrl 格式。
     * <p>
     * 必须为 http/https URL，不含用户信息（防日志泄露）、不含查询参数（防签名泄露）。
     */
    private static void validateBaseUrl(String raw) {
        String url = raw.trim();
        if (url.isEmpty()) return;

        // 必须以 http:// 或 https:// 开头
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "baseUrl 必须以 http:// 或 https:// 开头: " + url);
        }

        try {
            URI uri = new URI(url);
            // 必须有 host
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "baseUrl 缺少有效的主机名");
            }
            // 禁止在 baseUrl 中包含 userInfo（防止 AK 误填到此字段）
            if (uri.getUserInfo() != null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "baseUrl 不能包含用户信息（请使用 apiKey 字段填写密钥）");
            }
            // 不允许 /v1/chat/completions 等完整路径——只接受 API base
            String path = uri.getPath();
            if (path != null && path.matches(".*/(chat/completions|completions|v1/chat).*")) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "baseUrl 应为 API 基础地址（如 https://api.openai.com），不要包含 /v1/chat/completions 等路径");
            }
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "baseUrl 格式无效: " + e.getMessage());
        }
    }

    // ─── 脱敏 ──────────────────────────────────────

    private static String maskApiKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
