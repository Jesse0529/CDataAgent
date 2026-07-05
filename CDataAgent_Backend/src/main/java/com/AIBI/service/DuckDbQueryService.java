package com.AIBI.service;

import com.AIBI.config.DuckDbConfig;
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

    /**
     * 执行 DuckDB SQL 查询（多文件）。
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

            log.info("DuckDB 查询: {} 个视图, SQL={}",
                    files.size(),
                    finalSql.length() > 200 ? finalSql.substring(0, 200) + "..." : finalSql);

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
            log.warn("DuckDB 查询超时: {}", sql, e);
            return ToolResultUtils.jsonTypedError("timeout", "查询超时（超过" + duckDbConfig.getQueryTimeoutSeconds() + "秒），" +
                    "建议：① 减少数据量 ② 添加 WHERE 筛选 ③ 分步查询");
        } catch (java.sql.SQLSyntaxErrorException e) {
            log.warn("DuckDB 查询语法错误: {}", sql, e);
            String msg = e.getMessage();
            return ToolResultUtils.jsonTypedError("syntax", "查询语法错误" +
                    (msg != null ? ": " + msg : "") +
                    "。请用 getSchema 确认列名后重试");
        } catch (java.sql.SQLException e) {
            log.error("DuckDB 查询异常: {}", sql, e);
            String msg = e.getMessage();
            if (msg == null) msg = "";
            if (msg.contains("Parser Error") || msg.contains("syntax error")
                    || msg.contains("Binder Error")) {
                return ToolResultUtils.jsonTypedError("syntax", "查询语法错误" +
                        ": " + msg + "。请用 getSchema 确认后重试");
            }
            if (msg.contains("not found") || msg.contains("does not exist")
                    || msg.contains("Table") || msg.contains("Column")) {
                return ToolResultUtils.jsonTypedError("syntax", "列名或表名不存在" +
                        ": " + msg + "。请用 getSchema 确认后重试");
            }
            return ToolResultUtils.jsonTypedError("system", "数据引擎异常" +
                    (msg != null ? ": " + msg : "") + "，请重试");
        } catch (Exception e) {
            log.error("DuckDB 连接/系统异常: {}", sql, e);
            return ToolResultUtils.jsonTypedError("system", "数据引擎异常: " + e.getMessage() + "，请重试");
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
            log.error("数据预览查询失败: parquetPath={}, viewName={}", parquetPath, viewName, e);
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

        // Step 2: 检测多语句注入（分号，排除字符串字面量和末尾尾随分号）
        String semicolonCheck = removeStringLiterals(sql);
        int lastSemi = semicolonCheck.lastIndexOf(';');
        if (lastSemi >= 0) {
            // 检查分号后是否有非空内容（允许尾随分号和分号后的行注释）
            String afterLastSemi = semicolonCheck.substring(lastSemi + 1).trim();
            if (!afterLastSemi.isEmpty() && !afterLastSemi.startsWith("--")) {
                return "不允许执行多条 SQL 语句";
            }
        }

        // Step 3: 检查危险函数/模式
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(sql).find()) {
                log.warn("SQL 注入检测拦截: 匹配到危险模式 {} 在 SQL: {}", p, truncateSql(sql));
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

    /**
     * 将 SQL 中的字符串字面量替换为占位符，防止字符串内容干扰结构分析。
     */
    private static String removeStringLiterals(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
                sb.append(inSingleQuote ? '\'' : '\''); // 保留引号边界
            } else if (!inSingleQuote) {
                sb.append(c);
            }
            // 字符串内部全部替换为空格长度保持索引对齐（不依赖索引，直接忽略）
        }
        return sb.toString();
    }

    /**
     * 数值类型强制转换：整数保持 long，浮点保持 double。
     */
    /**
     * 截断长 SQL（日志输出用）。
     */
    private static String truncateSql(String sql) {
        return sql != null && sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }

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

}
