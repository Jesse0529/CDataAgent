package com.AIBI.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DuckDB 连接管理器 — 按 conversationId 缓存连接，轮次内复用。
 * <p>
 * 避免每轮多次 SQL 查询重复创建 DuckDB 实例。
 * 连接在 {@code doFinally} 中由 AgentServiceImpl 统一关闭。
 */
@Slf4j
@Component
public class DuckDbConnectionManager {

    /** conversationId → DuckDB Connection */
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();

    @Autowired
    private DuckDbConfig duckDbConfig;

    /**
     * 获取或创建指定对话的 DuckDB 连接。
     * <p>
     * 首次创建时自动配置 threads / memory_limit。
     */
    public Connection getOrCreate(String conversationId) {
        return connections.computeIfAbsent(conversationId, k -> {
            try {
                Connection conn = duckDbConfig.createBaseConnection();
                log.debug("DuckDB 连接已创建: conversationId={}", conversationId);
                return conn;
            } catch (Exception e) {
                throw new RuntimeException("创建 DuckDB 连接失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 在指定连接上注册 Parquet 视图。
     * <p>
     * CREATE OR REPLACE VIEW 是幂等操作，每次查询前调用确保视图始终与当前文件一致。
     */
    public void ensureViews(Connection conn, List<DuckDbConfig.FileRef> files) {
        if (files == null || files.isEmpty()) return;
        try (Statement stmt = conn.createStatement()) {
            for (DuckDbConfig.FileRef ref : files) {
                stmt.execute("CREATE OR REPLACE VIEW " + quoteId(ref.viewName()) +
                        " AS SELECT * FROM '" + ref.parquetPath().replace("'", "''") + "'");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB 视图注册失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭指定对话的 DuckDB 连接。
     */
    public void close(String conversationId) {
        if (conversationId == null) return;
        Connection conn = connections.remove(conversationId);
        if (conn != null) {
            try {
                conn.close();
                log.debug("DuckDB 连接已关闭: conversationId={}", conversationId);
            } catch (SQLException e) {
                log.warn("DuckDB 连接关闭失败: conversationId={}", conversationId, e);
            }
        }
    }

    /**
     * 关闭所有缓存连接（应用关闭时调用）。
     */
    public void closeAll() {
        for (String cid : connections.keySet()) {
            close(cid);
        }
    }

    private static String quoteId(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
