package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * DuckDB 配置。
 * DuckDB 是嵌入式数据库，每个查询创建独立的 in-memory 实例，
 * 通过注册 Parquet 文件为视图来访问数据。
 * <p>
 * 使用方法：
 * <pre>
 *   // 单文件
 *   Connection conn = createConnection("/path/to/file.parquet", "my_view");
 *
 *   // 多文件（支持 JOIN）
 *   List&lt;FileRef&gt; refs = List.of(
 *       new FileRef("/path/a.parquet", "data_view_a"),
 *       new FileRef("/path/b.parquet", "data_view_b")
 *   );
 *   Connection conn = createConnection(refs);
 * </pre>
 */
@Slf4j
@Configuration
public class DuckDbConfig {

    @Value("${duckdb.query.max-result-rows:1000}")
    private int maxResultRows;

    @Value("${duckdb.query.timeout-seconds:30}")
    private int queryTimeoutSeconds;

    @Value("${duckdb.query.threads:4}")
    private int threads;

    @Value("${duckdb.query.memory-limit:1GB}")
    private String memoryLimit;

    /**
     * 为指定的 Parquet 文件创建 DuckDB 连接并注册为视图。
     */
    public Connection createConnection(String parquetPath, String viewName) throws Exception {
        Connection conn = createBaseConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE VIEW " + quoteId(viewName) +
                    " AS SELECT * FROM '" + parquetPath.replace("'", "''") + "'");
        }
        log.debug("DuckDB 连接已创建: view={}", viewName);
        return conn;
    }

    /**
     * 为多个 Parquet 文件创建 DuckDB 连接，一次性注册全部视图。
     * <p>
     * 注册后各视图可在 SQL 中独立引用或 JOIN。
     */
    public Connection createConnection(List<FileRef> files) throws Exception {
        Connection conn = createBaseConnection();
        try (Statement stmt = conn.createStatement()) {
            for (FileRef ref : files) {
                stmt.execute("CREATE OR REPLACE VIEW " + quoteId(ref.viewName) +
                        " AS SELECT * FROM '" + ref.parquetPath.replace("'", "''") + "'");
            }
        }
        log.debug("DuckDB 连接已创建: {} 个视图", files.size());
        return conn;
    }

    /**
     * DuckDB identifier quoting：用双引号包裹，内部双引号用 "" 转义。
     */
    private static String quoteId(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /**
     * 创建纯 in-memory 连接（用于 CSV 转 Parquet 等不依赖已有 Parquet 的场景）。
     */
    public Connection createMemoryConnection() throws Exception {
        return createBaseConnection();
    }

    /**
     * 创建并配置基础 DuckDB 连接。
     */
    private Connection createBaseConnection() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        try {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET threads = " + threads);
                stmt.execute("SET memory_limit = '" + memoryLimit + "'");
            }
        } catch (Exception e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    public int getMaxResultRows() { return maxResultRows; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }

    /**
     * 文件引用：Parquet 路径 + DuckDB 视图名。
     */
    public record FileRef(String parquetPath, String viewName) {}
}
