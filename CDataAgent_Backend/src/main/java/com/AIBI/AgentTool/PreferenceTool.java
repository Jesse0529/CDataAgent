package com.AIBI.AgentTool;

import com.AIBI.manager.UserPreferenceManager;
import com.AIBI.utils.ToolResultUtils;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 用户偏好工具：供 Agent 读取/保存用户偏好。
 */
@Slf4j
@Component
public class PreferenceTool {

    private static final Set<String> CHART_TYPES = Set.of(
            "auto", "bar", "line", "area", "pie", "scatter", "radar", "funnel", "gauge", "heatmap");
    private static final Set<String> OUTPUT_STYLES = Set.of("brief", "detailed");

    @Autowired
    private UserPreferenceManager preferenceManager;

    @Tool(description = "读取已校验的长期偏好；当前用户请求优先。")
    public String getPreferences() {
        try {
            Map<String, String> prefs = preferenceManager.getAllPreferences();
            Map<String, String> safePreferences = new LinkedHashMap<>();
            if (prefs != null) {
                putIfAllowed(safePreferences, "chart_type", prefs.get("chart_type"));
                putIfAllowed(safePreferences, "output_style", prefs.get("output_style"));
            }
            return JSON.toJSONString(safePreferences);
        } catch (Exception e) {
            log.warn("读取用户偏好失败：异常={}", e.getClass().getSimpleName());
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_SYSTEM, "读取偏好失败，请稍后重试");
        }
    }

    @Tool(description = "保存长期偏好；仅在用户明确表达“以后/始终”等偏好时调用。chart_type 仅支持 auto/bar/line/area/pie/scatter/radar/funnel/gauge/heatmap，output_style 仅支持 brief/detailed。")
    public String savePreference(
            @ToolParam(description = "chart_type 或 output_style") String key,
            @ToolParam(description = "偏好值") String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank())
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_SYNTAX, "key 和 value 不能为空");

        String normalizedKey = key.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedValue = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!isAllowed(normalizedKey, normalizedValue)) {
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_SYNTAX,
                    "仅支持 chart_type（auto/bar/line/area/pie/scatter/radar/funnel/gauge/heatmap）"
                            + " 或 output_style（brief/detailed）");
        }

        String existing = preferenceManager.getPreference(normalizedKey);
        if (normalizedValue.equals(existing)) return "该偏好已保存，无需重复设置";

        try {
            preferenceManager.setPreference(normalizedKey, normalizedValue);
            log.info("用户偏好已保存：键={}", normalizedKey);
            return "偏好已保存";
        } catch (Exception e) {
            log.warn("保存用户偏好失败：异常={}", e.getClass().getSimpleName());
            return ToolResultUtils.jsonTypedError(ToolResultUtils.ERROR_SYSTEM, "保存偏好失败，请稍后重试");
        }
    }

    private static void putIfAllowed(Map<String, String> target, String key, String value) {
        if (isAllowed(key, value)) target.put(key, value);
    }

    private static boolean isAllowed(String key, String value) {
        if (key == null || value == null) return false;
        return ("chart_type".equals(key) && CHART_TYPES.contains(value))
                || ("output_style".equals(key) && OUTPUT_STYLES.contains(value));
    }
}
