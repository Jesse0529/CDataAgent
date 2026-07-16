package com.AIBI.AgentTool;

import com.AIBI.manager.UserPreferenceManager;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 用户偏好工具：供 Agent 读取/保存用户偏好。
 */
@Slf4j
@Component
public class PreferenceTool {

    @Autowired
    private UserPreferenceManager preferenceManager;

    @Tool(description = "读取长期偏好；当前用户请求优先。")
    public String getPreferences() {
        Map<String, String> prefs = preferenceManager.getAllPreferences();
        return JSON.toJSONString(prefs);
    }

    @Tool(description = "保存长期偏好；仅在用户明确表达“以后/始终”等偏好时调用。")
    public String savePreference(
            @ToolParam(description = "chart_type 或 output_style") String key,
            @ToolParam(description = "偏好值") String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank())
            return "保存失败：key 和 value 不能为空";

        String existing = preferenceManager.getPreference(key);
        if (value.equals(existing)) return "该偏好已保存，无需重复设置";

        preferenceManager.setPreference(key.trim(), value.trim());
        log.info("用户偏好已保存：键={}", key);
        return "偏好已保存";
    }
}
