package com.AIBI.utils;

import java.util.regex.Pattern;

/**
 * 分析结果引用的服务端校验规则。
 */
public final class OutputKeyPolicy {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private OutputKeyPolicy() {
    }

    public static boolean isValid(String outputKey) {
        return outputKey != null && KEY_PATTERN.matcher(outputKey).matches();
    }
}
