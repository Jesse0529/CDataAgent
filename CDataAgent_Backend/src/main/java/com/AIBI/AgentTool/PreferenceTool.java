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

    @Tool(description = "获取当前用户的已保存偏好，返回 JSON 对象。了解用户长期偏好（图表类型、输出风格），但用户当前输入优先。")
    public String getPreferences() {
        Map<String, String> prefs = preferenceManager.getAllPreferences();
        return JSON.toJSONString(prefs);
    }

    @Tool(description = "保存用户一项偏好设置。仅在用户明确表达长期偏好时调用（如「以后都用柱状图」）。调用前先检查是否已存在相同值。")
    public String savePreference(
            @ToolParam(description = "偏好键：chart_type(图表类型)/output_style(输出风格: simplified/detailed/balanced)") String key,
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
