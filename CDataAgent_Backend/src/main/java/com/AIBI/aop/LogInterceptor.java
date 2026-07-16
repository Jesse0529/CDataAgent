package com.AIBI.aop;

import java.util.Arrays;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求响应日志 AOP
 **/
@Aspect
@Component
@Slf4j
public class LogInterceptor {

    /** 日志中需要脱敏的参数名（不区分大小写） */
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "apikey", "api_key", "password", "secret", "token", "authorization");

    /**
     * 执行拦截
     */
    @Around("execution(* com.AIBI.controller.*.*(..))")
    public Object doInterceptor(ProceedingJoinPoint point) throws Throwable {
        // 计时
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // 获取请求路径
        RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes) requestAttributes).getRequest();
        // 生成请求唯一 id
        String requestId = UUID.randomUUID().toString();
        String url = httpServletRequest.getRequestURI();
        // 获取请求参数并脱敏
        Object[] args = point.getArgs();
        String reqParam = maskSensitive("[" + StringUtils.join(args, ", ") + "]");
        // 输出请求日志
        log.debug("请求开始，id: {}, path: {}, ip: {}", requestId, url,
                httpServletRequest.getRemoteHost());
        // 执行原方法
        Object result = point.proceed();
        // 输出响应日志
        stopWatch.stop();
        long totalTimeMillis = stopWatch.getTotalTimeMillis();
        log.debug("请求结束，id: {}, 耗时: {}ms", requestId, totalTimeMillis);
        return result;
    }

    /**
     * 对日志字符串中的敏感参数值进行脱敏处理。
     * 匹配 JSON 格式的 key-value，如 "apiKey":"sk-xxx" → "apiKey":"****"
     */
    private static String maskSensitive(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (String key : SENSITIVE_KEYS) {
            // 匹配 "key":"value" 或 "key": "value" 或 key=value 模式
            result = result.replaceAll(
                    "(?i)(\"" + key + "\"\\s*:\\s*\")[^\"]*(\")",
                    "$1****$2");
            result = result.replaceAll(
                    "(?i)(" + key + "=)[^&\\s,}]+",
                    "$1****");
        }
        return result;
    }
}

