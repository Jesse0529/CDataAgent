package com.AIBI.job;

import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.DataFile;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件清理定时任务（第一层：孤儿 + 卡住文件）。
 * <p>
 * 清理策略：
 * <ol>
 *   <li><b>盘扫描</b>：遍历存储目录所有 Parquet 文件，与 MySQL 中 READY 记录比对，
 *       无匹配则删除。覆盖软删除、对话删除、手动 SQL 删除等场景。</li>
 *   <li><b>卡住清理</b>：删除转换失败卡在 CONVERTING 状态超过 N 小时的记录和残留文件。</li>
 * </ol>
 * <p>
 * 默认每天凌晨 3 点执行，可通过 {@code file.cleanup.cron} 配置覆盖。
 * 不做"按活跃时间过期"清理——那是第二层的事，需要对话活跃时间字段支持。
 */
@Slf4j
@Component
public class FileCleanupJob {

    @Autowired
    private DataFileMapper dataFileMapper;

    @Value("${data.file.storage-dir:/data/cdata-files}")
    private String storageDir;

    @Value("${data.file.cleanup.stuck-hours:1}")
    private int stuckHours;

    private static final String PARQUET_EXT = ".parquet";

    /**
     * 每天凌晨 3 点执行，可通过配置覆盖。
     */
    @Scheduled(cron = "${data.file.cleanup.cron:0 0 3 * * ?}")
    public void cleanup() {
        log.info("FileCleanupJob: 开始执行");
        long start = System.currentTimeMillis();

        int orphans = cleanupDiskOrphans();
        int stuck = cleanupStuckConverting();

        long elapsed = System.currentTimeMillis() - start;
        log.info("FileCleanupJob: 完成 (孤儿文件={}, 卡住记录={}, 耗时={}ms)", orphans, stuck, elapsed);
    }

    // ─── 1. 盘扫描孤儿文件 ──────────────────────────

    /**
     * 遍历存储目录，删除 H2 中无对应 READY 记录的 Parquet 文件。
     * <p>
     * 覆盖场景：物理删除后的残留、对话已被删除、手动 SQL 删除等。
     *
     * @return 清理的孤儿文件数
     */
    private int cleanupDiskOrphans() {
        Path storageRoot = Paths.get(storageDir);
        if (!Files.exists(storageRoot)) {
            log.debug("FileCleanupJob: 存储目录不存在，跳过: {}", storageDir);
            return 0;
        }

        // 查询所有有效路径（一次性取出，避免 O(n) 查询）
        Set<String> validPaths;
        try {
            QueryWrapper<DataFile> qw = new QueryWrapper<>();
            qw.select("storagePath").eq("status", "READY");
            List<DataFile> activeFiles = dataFileMapper.selectList(qw);
            validPaths = activeFiles.stream()
                    .map(DataFile::getStoragePath)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("FileCleanupJob: 查询 MySQL 失败，跳过本次清理", e);
            return 0;
        }

        if (validPaths.isEmpty()) {
            log.debug("FileCleanupJob: 无活跃文件记录");
        }

        java.util.concurrent.atomic.AtomicInteger deleted = new java.util.concurrent.atomic.AtomicInteger(0);
        try (Stream<Path> files = Files.walk(storageRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(PARQUET_EXT))
                    .forEach(parquet -> {
                        if (!validPaths.contains(parquet.toString())) {
                            try {
                                Files.deleteIfExists(parquet);
                                deleted.incrementAndGet();
                                log.info("FileCleanupJob: 已删除孤儿文件: {}", parquet);
                            } catch (IOException e) {
                                log.warn("FileCleanupJob: 删除孤儿文件失败: {}", parquet, e);
                            }
                        }
                    });
        } catch (IOException e) {
            log.error("FileCleanupJob: 遍历存储目录失败", e);
        }

        // 清理空目录（倒序，先删子目录）
        if (deleted.get() > 0) {
            cleanEmptyDirs(storageRoot);
        }

        return deleted.get();
    }

    // ─── 2. 清理卡住的 CONVERTING 记录 ──────────────

    /**
     * 删除转换失败卡在 CONVERTING 状态超过 stuckHours 小时的记录和残留文件。
     * <p>
     * 正常转换应在数秒内完成。超过 1 小时还在 CONVERTING 说明进程已死。
     *
     * @return 清理的卡住记录数
     */
    private int cleanupStuckConverting() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(stuckHours);
            QueryWrapper<DataFile> qw = new QueryWrapper<>();
            qw.eq("status", "CONVERTING").lt("createTime", cutoff);
            List<DataFile> stuckFiles = dataFileMapper.selectList(qw);

            if (stuckFiles.isEmpty()) return 0;

            int count = 0;
            for (DataFile df : stuckFiles) {
                // 删除残留 Parquet（如果存在）
                if (df.getStoragePath() != null) {
                    try {
                        Files.deleteIfExists(Path.of(df.getStoragePath()));
                    } catch (IOException e) {
                        log.warn("FileCleanupJob: 删除残留文件失败: {}", df.getStoragePath());
                    }
                }
                // 删除数据库记录（物理删除）
                dataFileMapper.deleteById(df.getId());
                count++;
                log.info("FileCleanupJob: 已清理卡住记录: {} ({}小时前)",
                        df.getOriginalFilename(), stuckHours);
            }

            return count;
        } catch (Exception e) {
            log.error("FileCleanupJob: 清理卡住记录失败", e);
            return 0;
        }
    }

    // ─── 辅助 ──────────────────────────────────────

    /**
     * 递归删除空目录（叶子 → 根方向）。
     */
    private void cleanEmptyDirs(Path root) {
        try (Stream<Path> dirs = Files.walk(root)
                .filter(Files::isDirectory)
                .sorted((a, b) -> b.compareTo(a))) { // 倒序：先子后父
            dirs.forEach(dir -> {
                if (dir.equals(root)) return;
                try (Stream<Path> contents = Files.list(dir)) {
                    if (contents.findAny().isEmpty()) {
                        Files.delete(dir);
                        log.debug("FileCleanupJob: 已删除空目录: {}", dir);
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.debug("FileCleanupJob: 清理空目录跳过: {}", e.getMessage());
        }
    }
}
