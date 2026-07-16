package com.AIBI.AgentTool;

import com.AIBI.agent.model.AnalysisState;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Python 沙箱执行工具：供 ExecutorAgent 使用。
 * <p>
 * 在 Docker 沙箱中执行 Python 分析脚本。执行前进行 AST 静态安全检查。
 * 实际沙箱调用委托给框架提供的 {@link ToolCallback}。
 * 仅用于 Java 工具无法覆盖的复杂分析场景。
 */
@Slf4j
@Component
public class PythonRunnerTool {

    @Autowired
    private AnalysisState analysisState;

    /** 框架提供的沙箱执行 ToolCallback（由 SandboxConfig 注入） */
    @Autowired(required = false)
    private ToolCallback sandboxToolCallback;

    /** 禁止的 Python 导入 */
    private static final Set<String> FORBIDDEN_IMPORTS = Set.of(
            "os", "subprocess", "socket", "requests", "urllib", "shutil",
            "sys", "ctypes", "multiprocessing", "signal", "importlib", "builtins"
    );

    /** 禁止的代码模式 */
    private static final Pattern[] FORBIDDEN_PATTERNS = {
            Pattern.compile("while\\s+True", Pattern.CASE_INSENSITIVE),
            Pattern.compile("__import__\\s*\\("),
            Pattern.compile("eval\\s*\\("),
            Pattern.compile("exec\\s*\\("),
            Pattern.compile("compile\\s*\\("),
            Pattern.compile("\\.system\\s*\\("),
    };

    /**
     * 在沙箱中执行 Python 分析脚本。
     */
    @Tool(description = "在 Docker 沙箱运行 Python，仅用于相关性、统计检验、聚类或复杂公式；输出 print(JSON)。")
    public String runPython(
            @ToolParam(description = "Python 脚本") String code,
            @ToolParam(description = "结果 outputKey") String outputKey) {
        try {
            // 1. AST 安全检查
            String validationError = validateCode(code);
            if (validationError != null) {
                return jsonError("代码安全检查失败: " + validationError);
            }

            // 2. 沙箱不可用时降级
            if (sandboxToolCallback == null) {
                return jsonError("沙箱未启用。请使用 DuckDB 工具（runDuckdb, queryStatistics）完成分析，" +
                        "或启用 Docker 沙箱后重试。");
            }

            // 3. 调用框架沙箱工具
            String toolInput = "{\"code\":\"" + escapeJson(code) + "\"}";
            String output = sandboxToolCallback.call(toolInput);

            // 4. 存入分析状态
            analysisState.addStepResult(outputKey, "runPython", 1, null);

            JSONObject result = new JSONObject();
            result.put("outputKey", outputKey);
            result.put("type", "python_result");
            result.put("result", output);

            log.info("Python执行：输出键={}、输出大小={}", outputKey,
                    output != null ? output.length() : 0);
            return result.toJSONString();

        } catch (Exception e) {
            log.error("Python执行失败", e);
            analysisState.addStepResultFailed(outputKey, "runPython", e.getMessage());
            return jsonError("Python 执行失败: " + e.getMessage());
        }
    }

    private String validateCode(String code) {
        for (String forbidden : FORBIDDEN_IMPORTS) {
            Pattern p = Pattern.compile("^\\s*import\\s+" + forbidden + "\\b|^\\s*from\\s+" + forbidden + "\\s+import",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
            if (p.matcher(code).find()) return "禁止导入模块: " + forbidden;
        }
        for (Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(code).find()) return "禁止使用: " + pattern.pattern();
        }
        if (!code.contains("print(") && !code.contains("json.dump"))
            return "代码必须包含输出语句（print 或 json.dump）";
        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String jsonError(String msg) {
        JSONObject err = new JSONObject(); err.put("error", msg); return err.toJSONString();
    }
}
