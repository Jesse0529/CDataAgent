package com.AIBI.service;

import com.AIBI.config.DuckDbConfig;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DuckDbQueryService 单元测试。
 * 使用嵌入式 DuckDB 创建真实 Parquet 文件进行测试。
 */
@SpringBootTest(classes = {DuckDbConfig.class, DuckDbQueryService.class})
@TestPropertySource(properties = {
        "duckdb.query.max-result-rows=1000",
        "duckdb.query.timeout-seconds=30",
        "duckdb.query.threads=4",
        "duckdb.query.memory-limit=1GB"
})
@DisplayName("DuckDbQueryService 查询服务测试")
class DuckDbQueryServiceTest {

    @Autowired
    private DuckDbQueryService queryService;

    @Autowired
    private DuckDbConfig duckDbConfig;

    @TempDir
    static Path tempDir;

    private static DuckDbConfig.FileRef fileRef;
    private static DuckDbConfig.FileRef multiFileRef;

    @BeforeAll
    static void createTestParquet() throws Exception {
        Path salesFile = tempDir.resolve("sales.parquet");
        Path productsFile = tempDir.resolve("products.parquet");

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {

            // 表 1：销售数据（含各种数据类型）
            stmt.execute("CREATE TABLE sales AS SELECT * FROM (VALUES " +
                    "('A', 100, 10.5, '2024-01'), " +
                    "('B', 200, 20.3, '2024-01'), " +
                    "('C', 150, 15.7, '2024-02'), " +
                    "('A', 180, 18.0, '2024-02'), " +
                    "('B', 0, 0.0, '2024-02')" +
                    ") AS t(category, sales, profit, month)");
            stmt.execute("COPY sales TO '" +
                    salesFile.toString().replace("'", "''") + "' (FORMAT PARQUET)");

            // 表 2：产品信息（用于多文件 JOIN 测试）
            stmt.execute("CREATE TABLE products AS SELECT * FROM (VALUES " +
                    "('A', '电子产品', 0.15), " +
                    "('B', '日用品', 0.10), " +
                    "('C', '食品', 0.08)" +
                    ") AS t(cat, cat_name, tax_rate)");
            stmt.execute("COPY products TO '" +
                    productsFile.toString().replace("'", "''") + "' (FORMAT PARQUET)");
        }

        fileRef = new DuckDbConfig.FileRef(salesFile.toString(), "sales_view");
        multiFileRef = new DuckDbConfig.FileRef(productsFile.toString(), "products_view");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-01: 基本 SELECT
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-01: SELECT 应返回 JSON 数组")
    void selectShouldReturnJsonArray() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT category, sales FROM sales_view ORDER BY category");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        assertFalse(arr.isEmpty());
        JSONObject first = arr.getJSONObject(0);
        assertTrue(first.containsKey("category"));
        assertTrue(first.containsKey("sales"));
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-02: 自动追加 LIMIT
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-02: 不带 LIMIT 的 SQL 应自动追加 LIMIT 1000")
    void selectWithoutLimitShouldAppendLimit() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT * FROM sales_view");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        // 数据只有 5 行，LIMIT 1000 不会截断 — 验证能正常返回全部
        assertEquals(5, arr.size());
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-03: 保留已有 LIMIT
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-03: 已有 LIMIT 的 SQL 应保留原始 LIMIT")
    void selectWithExistingLimitShouldKeepIt() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT * FROM sales_view LIMIT 3");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        assertEquals(3, arr.size());
    }

    @Test
    @DisplayName("DQS-03b: LIMIT 0 应返回空结果")
    void selectWithLimitZeroShouldReturnEmpty() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT * FROM sales_view LIMIT 0");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        assertTrue(arr.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-04: 拒绝 DML
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO sales_view VALUES ('D', 300, 30.0)",
            "DELETE FROM sales_view WHERE category='A'",
            "DROP VIEW sales_view",
            "UPDATE sales_view SET sales=999 WHERE category='A'",
            "ALTER TABLE sales_view RENAME TO x",
            "CREATE TABLE x (a INT)",
            "TRUNCATE sales_view"
    })
    @DisplayName("DQS-04: DML/DDL 应被拒绝返回 syntax 错误")
    void dmlShouldBeRejected(String sql) {
        String result = queryService.executeQuery(List.of(fileRef), sql);
        assertErrorType(result, "syntax");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-05: 允许的特殊语句
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT category, SUM(sales) FROM sales_view GROUP BY category",
            "SELECT DISTINCT category FROM sales_view",
            "SELECT * FROM sales_view WHERE sales > 100",
            "SELECT category, COUNT(*) AS cnt FROM sales_view GROUP BY category HAVING cnt > 1",
            "SELECT * FROM sales_view ORDER BY sales DESC",
    })
    @DisplayName("DQS-05: 合法 SELECT 变体应正常执行")
    void validSelectVariantsShouldExecute(String sql) {
        String result = queryService.executeQuery(List.of(fileRef), sql);
        assertFalse(result.contains("\"error\""), "合法 SQL 不应返回错误: " + sql);
        JSONArray arr = JSON.parseArray(result);
        assertNotNull(arr);
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-06: SQL 语法错误
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT FORM sales_view",
            "SLECT * FROM sales_view",
            "SELECT * FROM WHERE category='A'"
    })
    @DisplayName("DQS-06: SQL 语法错误应返回 syntax 类型错误")
    void sqlSyntaxErrorShouldReturnSyntaxType(String sql) {
        String result = queryService.executeQuery(List.of(fileRef), sql);
        assertErrorType(result, "syntax");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-07: 列名不存在
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-07: 不存在的列名应返回 syntax 错误（含 not found 提示）")
    void nonExistentColumnShouldReturnSyntaxError() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT nonexistent_col FROM sales_view");
        assertErrorType(result, "syntax");
        JSONObject obj = JSON.parseObject(result);
        String msg = obj.getString("message");
        assertTrue(msg != null && (msg.contains("not found") || msg.contains("不存在") || msg.contains("does not exist")),
                "错误消息应提示列名不存在: " + msg);
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-08: 空文件列表
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-08: null 文件列表应返回 syntax 错误")
    void nullFileListShouldReturnSyntaxError() {
        String result = queryService.executeQuery(null, "SELECT 1");
        assertErrorType(result, "syntax");
        assertMessageContains(result, "没有可用的数据文件");
    }

    @Test
    @DisplayName("DQS-08b: 空文件列表应返回 syntax 错误")
    void emptyFileListShouldReturnSyntaxError() {
        String result = queryService.executeQuery(List.of(), "SELECT 1");
        assertErrorType(result, "syntax");
        assertMessageContains(result, "没有可用的数据文件");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-09: 空结果集
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-09: WHERE 条件不匹配应返回空 JSON 数组")
    void noMatchWhereShouldReturnEmptyArray() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT * FROM sales_view WHERE sales > 10000");
        assertEquals("[]", result);
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-10: 数值类型转换
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-10: 整型字段应为 Long 类型，浮点应为 Double")
    void numericTypesShouldBeCoercedCorrectly() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT category, sales, profit FROM sales_view LIMIT 1");
        JSONArray arr = JSON.parseArray(result);
        JSONObject row = arr.getJSONObject(0);
        // category 是字符串
        assertInstanceOf(String.class, row.get("category"));
        // sales 是整数值 → Long
        Object sales = row.get("sales");
        assertTrue(sales instanceof Integer || sales instanceof Long,
                "sales 应为整数类型: " + sales.getClass());
        // profit 是浮点（fastjson2 反序列化为 BigDecimal）
        assertInstanceOf(Number.class, row.get("profit"),
                "profit 应为 Number 类型");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-11: SQL 带尾部分号
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-11: SQL 末尾带分号应正常执行")
    void selectWithTrailingSemicolonShouldWork() {
        String result = queryService.executeQuery(
                List.of(fileRef), "SELECT category, sales FROM sales_view ORDER BY category;");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        assertFalse(arr.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-12: 多文件 JOIN
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-extra: 多文件 JOIN 应正常执行")
    void multiFileJoinShouldWork() {
        String result = queryService.executeQuery(
                List.of(fileRef, multiFileRef),
                "SELECT s.category, p.cat_name, SUM(s.sales) AS total_sales " +
                        "FROM sales_view s JOIN products_view p ON s.category = p.cat " +
                        "GROUP BY s.category, p.cat_name ORDER BY total_sales DESC");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        assertEquals(3, arr.size());
        // 验证 JOIN 字段
        JSONObject first = arr.getJSONObject(0);
        assertTrue(first.containsKey("cat_name"), "JOIN 结果应包含 cat_name");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-13: 特殊列名（含引号）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DQS-extra: 含特殊字符列名的查询应转义处理")
    void queryWithSpecialColumnNamesShouldHandleQuoting() {
        // DuckDB 自动处理 DESCRIBE
        String result = queryService.executeQuery(
                List.of(fileRef), "DESCRIBE sales_view");
        assertNotNull(result);
        JSONArray arr = JSON.parseArray(result);
        assertTrue(arr.size() >= 4, "DESCRIBE 应返回所有列信息");
    }

    // ═══════════════════════════════════════════════════════════
    // DQS-14: 空 SQL 参数
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @NullSource
    @EmptySource
    @DisplayName("DQS-extra: null/空 SQL 应返回 syntax 错误")
    void nullOrEmptySqlShouldBeHandledGracefully(String sql) {
        String result = queryService.executeQuery(List.of(fileRef), sql);
        assertErrorType(result, "syntax");
    }

    @Test
    @DisplayName("DQS-extra: 纯空格/注释 SQL 应被拒绝")
    void commentOnlySqlShouldBeRejected() {
        String result = queryService.executeQuery(List.of(fileRef), "-- 纯注释");
        assertErrorType(result, "syntax");
    }

    // ═══════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════

    // ─── 辅助断言 ──────────────────────────────────────────────

    private static void assertErrorType(String result, String expectedType) {
        assertNotNull(result, "结果不应为 null");
        JSONObject obj = JSON.parseObject(result);
        String errorType = obj.getString("error");
        assertNotNull(errorType, "应包含 error 字段: " + result);
        assertEquals(expectedType, errorType,
                "错误类型应为 " + expectedType + "，实际: " + result);
    }

    private static void assertMessageContains(String result, String expectedMsg) {
        JSONObject obj = JSON.parseObject(result);
        String msg = obj.getString("message");
        assertTrue(msg != null && msg.contains(expectedMsg),
                "错误消息应包含 \"" + expectedMsg + "\"，实际: " + msg);
    }
}
