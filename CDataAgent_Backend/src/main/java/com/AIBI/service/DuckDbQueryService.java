package com.AIBI.service;

import com.AIBI.config.DuckDbConfig;
import com.AIBI.config.DuckDbConnectionManager;
import com.AIBI.utils.ToolResultUtils;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DuckDB 查询服务。
 * <p>
 * 对已注册的 Parquet 视图执行 SELECT 查询，返回 JSON 格式结果。
 * 安全校验：仅允许 SELECT/DESCRIBE/SHOW/EXPLAIN，自动追加 LIMIT。
 * <p>
 * 多文件支持：每次查询可传入多个 Parquet 文件，DuckDB 一次性注册全部视图，
 * SQL 中通过 viewName 引用具体文件，支持跨文件 JOIN。
 */
@Slf4j
@Service
public class DuckDbQueryService {

    @Autowired
    private DuckDbConfig duckDbConfig;

    @Autowired
    private DuckDbConnectionManager connectionManager;

    /**
     * 执行 DuckDB SQL 查询（多文件，复用连接）。
     * <p>
     * 使用 DuckDbConnectionManager 缓存连接，避免每轮重复创建 DuckDB 实例。
     * 视图每次重新注册（CREATE OR REPLACE VIEW 是幂等操作），确保文件切换后视图一致。
     *
     * @param conversationId 对话 ID（用于缓存连接）
     * @param files          Parquet 文件引用列表（路径 + 视图名）
     * @param sql            SQL 查询语句（仅允许 SELECT）
     * @return JSON 数组字符串，失败时返回 {"error":"type","message":"..."}
     */
    public String executeQuery(String conversationId, List<DuckDbConfig.FileRef> files, String sql) {
        return executeQueryInternal(conversationId, files, sql, duckDbConfig.getMaxResultRows(), false).dataJson();
    }

    /**
     * Agent 查询专用入口。结果会保存到 AnalysisState，故采用独立且更小的返回行数上限。
     */
    public AgentQueryResult executeAgentQuery(String conversationId, List<DuckDbConfig.FileRef> files,
                                              String sql, int agentMaxRows) {
        int effectiveMaxRows = Math.max(1, Math.min(agentMaxRows, duckDbConfig.getMaxResultRows()));
        QueryExecution execution = executeQueryInternal(conversationId, files, sql, effectiveMaxRows, true);
        return new AgentQueryResult(execution.dataJson(), execution.rowCount(),
                execution.truncated(), effectiveMaxRows);
    }

    private QueryExecution executeQueryInternal(String conversationId, List<DuckDbConfig.FileRef> files,
                                                String sql, int maxRows, boolean detectTruncation) {
        if (files == null || files.isEmpty()) {
            return QueryExecution.error(ToolResultUtils.jsonTypedError("syntax", "没有可用的数据文件"));
        }

        // 安全校验
        String validationError = validateSql(sql);
        if (validationError != null) {
            return QueryExecution.error(ToolResultUtils.jsonTypedError("syntax", validationError));
        }

        Connection conn = connectionManager.getOrCreate(conversationId);
        connectionManager.ensureViews(conn, files);

        try (Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(duckDbConfig.getQueryTimeoutSeconds());
            if (detectTruncation) {
                stmt.setMaxRows(maxRows + 1);
            }
            // 自动追加 LIMIT
            String finalSql = sql.trim();
            String upperSql = finalSql.toUpperCase();
            if (!detectTruncation && upperSql.startsWith("SELECT") && !upperSql.contains("LIMIT")) {
                finalSql = finalSql.replaceAll(";\\s*$", "");
                finalSql += " LIMIT " + maxRows;
            }

            log.debug("DuckDB查询开始：视图数={}", files.size());

            try (ResultSet rs = stmt.executeQuery(finalSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                JSONArray result = new JSONArray();
                int rowCount = 0;
                boolean truncated = false;
                while (rs.next()) {
                    if (rowCount >= maxRows) {
                        truncated = true;
                        break;
                    }
                    JSONObject row = new JSONObject();
                    for (int i = 1; i <= colCount; i++) {
                        String colName = meta.getColumnLabel(i);
                        String value = rs.getString(i);
                        row.put(colName, value == null ? null : coerceNumber(value));
                    }
                    result.add(row);
                    rowCount++;
                }

                log.debug("DuckDB 查询结果: {} 行, {} 列, 截断={}", rowCount, colCount, truncated);
                return new QueryExecution(result.toJSONString(), rowCount, truncated);
            }
        } catch (java.sql.SQLTimeoutException e) {
            log.warn("DuckDB查询失败：类型=timeout");
            return QueryExecution.error(ToolResultUtils.jsonTypedError("timeout", "查询超时（超过" + duckDbConfig.getQueryTimeoutSeconds() + "秒），" +
                    "建议：① 减少数据量 ② 添加 WHERE 筛选 ③ 分步查询"));
        } catch (java.sql.SQLSyntaxErrorException e) {
            log.warn("DuckDB查询失败：类型=syntax");
            String msg = e.getMessage();
            String hint = buildSyntaxHint(msg);
            return QueryExecution.error(ToolResultUtils.jsonTypedError("syntax", "查询语法错误。" + hint));
        } catch (java.sql.SQLException e) {
            log.warn("DuckDB查询失败：类型=sql");
            String msg = e.getMessage();
            if (msg == null) msg = "";
            // 函数不存在（Catalog Error: Scalar Function ... does not exist!）
            if (msg.contains("Catalog Error") && msg.contains("Scalar Function")) {
                String hint = buildSyntaxHint(msg);
                return QueryExecution.error(ToolResultUtils.jsonTypedError("syntax", "查询语法错误。" + hint));
            }
            if (msg.contains("not found") || msg.contains("does not exist")
                    || msg.contains("Table") || msg.contains("Column")) {
                String hint = buildColumnHint(msg);
                return QueryExecution.error(ToolResultUtils.jsonTypedError("syntax", "列名或表名不存在。" + hint));
            }
            if (msg.contains("Parser Error") || msg.contains("syntax error")
                    || msg.contains("Binder Error")) {
                String hint = buildSyntaxHint(msg);
                return QueryExecution.error(ToolResultUtils.jsonTypedError("syntax", "查询语法错误。" + hint));
            }
            return QueryExecution.error(ToolResultUtils.jsonTypedError("system", "数据引擎异常，请稍后重试"));
        } catch (Exception e) {
            log.warn("DuckDB查询失败：类型=system、异常={}", e.getClass().getSimpleName());
            return QueryExecution.error(ToolResultUtils.jsonTypedError("system", "数据引擎异常，请稍后重试"));
        }
    }

    /** Agent 查询结果，截断状态仅在 Agent 专用入口中提供。 */
    public record AgentQueryResult(String dataJson, int rowCount, boolean truncated, int rowLimit) {
        public boolean hasError() {
            return ToolResultUtils.isError(dataJson);
        }
    }

    private record QueryExecution(String dataJson, int rowCount, boolean truncated) {
        private static QueryExecution error(String dataJson) {
            return new QueryExecution(dataJson, 0, false);
        }
    }

    /**
     * 执行 DuckDB SQL 查询（多文件，每次都新建连接）。
     * <p>
     * 与 {@link #executeQuery(String, List, String)} 的区别是不缓存连接，
     * 用于 {@link #previewData} 等非 Agent 工具调用场景。
     *
     * @param files Parquet 文件引用列表（路径 + 视图名）
     * @param sql   SQL 查询语句（仅允许 SELECT）
     * @return JSON 数组字符串，失败时返回 {"error":"type","message":"..."}
     */
    public String executeQuery(List<DuckDbConfig.FileRef> files, String sql) {
        if (files == null || files.isEmpty()) {
            return ToolResultUtils.jsonTypedError("syntax", "没有可用的数据文件");
        }

        // 安全校验
        String validationError = validateSql(sql);
        if (validationError != null) {
            return ToolResultUtils.jsonTypedError("syntax", validationError);
        }

        try (Connection conn = duckDbConfig.createConnection(files);
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(duckDbConfig.getQueryTimeoutSeconds());
            int maxRows = duckDbConfig.getMaxResultRows();

            // 自动追加 LIMIT
            String finalSql = sql.trim();
            String upperSql = finalSql.toUpperCase();
            if (upperSql.startsWith("SELECT") && !upperSql.contains("LIMIT")) {
                finalSql = finalSql.replaceAll(";\\s*$", "");
                finalSql += " LIMIT " + maxRows;
            }

            log.debug("DuckDB查询开始：视图数={}", files.size());

            try (ResultSet rs = stmt.executeQuery(finalSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                JSONArray result = new JSONArray();
                int rowCount = 0;
                while (rs.next() && rowCount < maxRows) {
                    JSONObject row = new JSONObject();
                    for (int i = 1; i <= colCount; i++) {
                        String colName = meta.getColumnLabel(i);
                        String value = rs.getString(i);
                        row.put(colName, value == null ? null : coerceNumber(value));
                    }
                    result.add(row);
                    rowCount++;
                }

                log.debug("DuckDB 查询结果: {} 行, {} 列", rowCount, colCount);
                return result.toJSONString();
            }
        } catch (java.sql.SQLTimeoutException e) {
            log.warn("DuckDB查询失败：类型=timeout");
            return ToolResultUtils.jsonTypedError("timeout", "查询超时（超过" + duckDbConfig.getQueryTimeoutSeconds() + "秒），" +
                    "建议：① 减少数据量 ② 添加 WHERE 筛选 ③ 分步查询");
        } catch (java.sql.SQLSyntaxErrorException e) {
            log.warn("DuckDB查询失败：类型=syntax");
            String msg = e.getMessage();
            return ToolResultUtils.jsonTypedError("syntax",
                    "查询语法错误。" + buildSyntaxHint(msg));
        } catch (java.sql.SQLException e) {
            log.warn("DuckDB查询失败：类型=sql");
            String msg = e.getMessage();
            if (msg == null) msg = "";
            if (msg.contains("Catalog Error") && msg.contains("Scalar Function")) {
                return ToolResultUtils.jsonTypedError("syntax",
                        "查询语法错误。" + buildSyntaxHint(msg));
            }
            if (msg.contains("not found") || msg.contains("does not exist")
                    || msg.contains("Table") || msg.contains("Column")) {
                return ToolResultUtils.jsonTypedError("syntax",
                        "列名或表名不存在。" + buildColumnHint(msg));
            }
            if (msg.contains("Parser Error") || msg.contains("syntax error")
                    || msg.contains("Binder Error")) {
                return ToolResultUtils.jsonTypedError("syntax",
                        "查询语法错误。" + buildSyntaxHint(msg));
            }
            return ToolResultUtils.jsonTypedError("system", "数据引擎异常，请稍后重试");
        } catch (Exception e) {
            log.warn("DuckDB查询失败：类型=system、异常={}", e.getClass().getSimpleName());
            return ToolResultUtils.jsonTypedError("system", "数据引擎异常，请稍后重试");
        }
    }

    /**
     * 数据预览 — 分页查询 Parquet 文件的数据行。
     * <p>
     * 用于前端文件点击预览场景，返回列名+数据行+分页信息。
     *
     * @param parquetPath Parquet 文件路径
     * @param viewName    DuckDB 视图名
     * @param page        页码（从 1 开始）
     * @param size        每页行数（最大 200）
     * @return 预览数据 VO
     */
    public com.AIBI.model.vo.FilePreviewVO previewData(String parquetPath, String viewName, int page, int size) {
        if (size <= 0 || size > 200) size = 30;
        if (page <= 0) page = 1;

        try (Connection conn = duckDbConfig.createConnection(parquetPath, viewName);
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(duckDbConfig.getQueryTimeoutSeconds());

            // 获取总行数
            int totalRows;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + viewName.replace("\"", "\"\"") + "\"")) {
                rs.next();
                totalRows = rs.getInt(1);
            }

            // 分页查询数据
            int offset = (page - 1) * size;
            String sql = "SELECT * FROM \"" + viewName.replace("\"", "\"\"") + "\" LIMIT " + size + " OFFSET " + offset;

            java.util.List<String> headers = new java.util.ArrayList<>();
            java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();

            try (ResultSet rs = stmt.executeQuery(sql)) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    headers.add(meta.getColumnLabel(i));
                }
                while (rs.next()) {
                    java.util.List<Object> row = new java.util.ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
            }

            com.AIBI.model.vo.FilePreviewVO vo = new com.AIBI.model.vo.FilePreviewVO();
            vo.setHeaders(headers);
            vo.setRows(rows);
            vo.setTotalRows(totalRows);
            vo.setPage(page);
            vo.setPageSize(size);
            vo.setHasMore(offset + size < totalRows);
            return vo;

        } catch (Exception e) {
            log.warn("数据预览查询失败：异常={}", e.getClass().getSimpleName());
            throw new RuntimeException("数据预览查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取单表 schema（供 Agent getSchema 工具使用）。
     */
    public String describeTable(String parquetPath, String viewName) throws Exception {
        try (Connection conn = duckDbConfig.createConnection(parquetPath, viewName);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("DESCRIBE " + viewName)) {

            JSONArray result = new JSONArray();
            while (rs.next()) {
                JSONObject col = new JSONObject();
                col.put("name", rs.getString("column_name"));
                col.put("type", rs.getString("column_type"));
                result.add(col);
            }
            return result.toJSONString();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SQL 安全校验（注入防护）
    // ═══════════════════════════════════════════════════════════

    /** 允许的 SQL 顶层关键字（白名单） */
    private static final Pattern ALLOWED_START_KEYWORD = Pattern.compile(
            "^\\s*(SELECT|DESCRIBE|SHOW|EXPLAIN|WITH)\\b", Pattern.CASE_INSENSITIVE);

    /** 禁止在用户 SQL 中出现的危险函数/模式（匹配时无视大小写和引号） */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            // 读取任意文件
            Pattern.compile("\\bread_(?:csv|parquet|json|text)\\s*\\(", Pattern.CASE_INSENSITIVE),
            // 文件系统操作
            Pattern.compile("\\b(?:COPY|EXPORT|IMPORT)\\s+(?:FROM|TO|DATABASE)\\b", Pattern.CASE_INSENSITIVE),
            // 文件 glob 扫描
            Pattern.compile("\\bglob\\s*\\(", Pattern.CASE_INSENSITIVE),
            // 数据库管理操作
            Pattern.compile("\\b(?:LOAD|INSTALL|ATTACH|DETACH|CREATE\\s+OR\\s+REPLACE)\\b", Pattern.CASE_INSENSITIVE),
            // 写操作白名单的反面：DDL/DML
            Pattern.compile("^\\s*(?:INSERT|DELETE|UPDATE|DROP|ALTER|TRUNCATE|CREATE|REPLACE)\\b", Pattern.CASE_INSENSITIVE),
            // 系统/权限相关
            Pattern.compile("\\bPRAGMA\\b", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 校验 SQL 语句的安全性。
     *
     * @return null 表示安全，非 null 返回错误描述
     */
    private static String validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "SQL 语句不能为空";
        }

        // Step 1: 去除前导注释，检查顶层关键字
        String withoutLeadingComments = stripLeadingComments(sql);
        if (withoutLeadingComments.isEmpty()) {
            return "SQL 格式无效（仅包含注释）";
        }

        if (!ALLOWED_START_KEYWORD.matcher(withoutLeadingComments).find()) {
            return "仅允许 SELECT/DESCRIBE/SHOW/EXPLAIN/WITH 查询";
        }

        // Step 2: 仅允许单条 SQL 和可选尾部分号。不能只检查最后一个分号，
        // 否则 "SELECT ...; DROP ...;" 会被末尾分号绕过。
        if (containsExtraStatement(sql)) {
            return "不允许执行多条 SQL 语句";
        }

        // Step 3: 检查危险函数/模式
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(sql).find()) {
                log.warn("SQL安全校验已拦截危险模式");
                return "SQL 中包含不被允许的操作";
            }
        }

        return null; // 校验通过
    }

    /**
     * 去除 SQL 语句前导的注释（行注释 -- 和块注释 /* *​/）。
     * 只处理第一个有效 token 之前的注释，不处理嵌入注释。
     */
    private static String stripLeadingComments(String sql) {
        String s = sql.trim();
        boolean changed;
        do {
            changed = false;
            // 行注释
            if (s.startsWith("--")) {
                int eol = s.indexOf('\n');
                s = (eol >= 0 ? s.substring(eol + 1) : "").trim();
                changed = true;
            }
            // 块注释
            if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) return ""; // 未闭合的注释
                s = s.substring(end + 2).trim();
                changed = true;
            }
        } while (changed && !s.isEmpty());
        return s;
    }

    /** 检查分号后是否还有有效 SQL，忽略字符串和注释中的分号。 */
    private static boolean containsExtraStatement(String sql) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingleQuote) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"' && next == '"') {
                    i++;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (c == ';') {
                return hasMeaningfulSql(sql, i + 1);
            }
        }
        return false;
    }

    private static boolean hasMeaningfulSql(String sql, int start) {
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (Character.isWhitespace(c)) continue;
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            return true;
        }
        return false;
    }

    /** 数值类型强制转换：整数保持 long，浮点保持 double。 */
    private static Object coerceNumber(String value) {
        try {
            double d = Double.parseDouble(value);
            if (d == Math.floor(d) && !value.contains(".") && value.length() < 18) {
                return (long) d;
            }
            return d;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SQL 错误提示增强（帮助 LLM 快速定位并修正 SQL）
    // ═══════════════════════════════════════════════════════════

    /**
     * 解析 DuckDB 语法错误消息，返回针对 LLM 的可行动提示。
     */
    private static String buildSyntaxHint(String errorMsg) {
        if (errorMsg == null) return "请检查 SQL 语句中的括号、逗号、关键字拼写，确认后重试。";
        String lowMsg = errorMsg.toLowerCase();

        if (lowMsg.contains("group by") || lowMsg.contains("must appear in")) {
            return "GROUP BY 子句缺少非聚合列，请将 SELECT 中的非聚合列全部添加到 GROUP BY 中。";
        }
        if (lowMsg.contains("function") && (lowMsg.contains("not recognized") || lowMsg.contains("not found") || lowMsg.contains("does not exist"))) {
            return "函数名不正确，请确认使用的 DuckDB 函数名是否正确（如 SUM/AVG/COUNT/MIN/MAX）。";
        }
        if (lowMsg.contains("unterminated")) {
            return "字符串或标识符未正确闭合，请检查单引号和双引号是否成对出现。";
        }
        if (lowMsg.contains("expected")) {
            return "SQL 语法结构不完整，请检查关键字顺序和括号是否匹配。";
        }
        return "请检查 SQL 语句的语法（括号匹配、逗号位置、关键字拼写），如有疑问请先调用 getSchema 确认列名和类型。";
    }

    /**
     * 解析 DuckDB "列名/表名不存在" 错误，返回指向 getSchema 的提示。
     */
    private static String buildColumnHint(String errorMsg) {
        if (errorMsg == null) return "请先调用 getSchema 确认正确的列名和 viewName，再重新编写 SQL。";

        String lowMsg = errorMsg.toLowerCase();
        // 提取出错的列名或表名
        String badName = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(errorMsg);
        if (m.find()) badName = m.group(1);

        if (lowMsg.contains("column")) {
            return (badName != null
                    ? "列名「" + badName + "」不存在。"
                    : "列名不存在。")
                    + "请先调用 getSchema 确认当前表中真实的列名（注意大小写和特殊字符），再按正确的列名重写 SQL。";
        }
        if (lowMsg.contains("table") || lowMsg.contains("view")) {
            return (badName != null
                    ? "表/视图「" + badName + "」不存在。"
                    : "表或视图不存在。")
                    + "请先调用 loadData 确认当前的 viewName，再使用正确的 viewName 重写 SQL。";
        }
        return "请先调用 getSchema 确认正确的列名和表名，再重新编写 SQL。";
    }

}
