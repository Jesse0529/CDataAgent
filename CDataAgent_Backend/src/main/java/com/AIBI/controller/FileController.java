package com.AIBI.controller;

import com.AIBI.agent.model.AnalysisState;
import com.AIBI.agent.run.AgentLockKeys;
import com.AIBI.common.BaseResponse;
import com.AIBI.common.ErrorCode;
import com.AIBI.common.ResultUtils;
import com.AIBI.exception.BusinessException;
import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.DataFile;
import com.AIBI.model.vo.DataFileVO;
import com.AIBI.model.vo.FilePreviewVO;
import com.AIBI.service.DuckDbQueryService;
import com.AIBI.service.FileConversionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 数据文件上传与管理接口。
 * <p>
 * 文件与对话绑定：一次上传多个文件，绑定到一个对话。重新上传会替换该对话的旧文件。
 */
@RestController
@RequestMapping("/apis/file")
@Slf4j
public class FileController {

    @Autowired
    private FileConversionService fileConversionService;

    @Autowired
    private DataFileMapper dataFileMapper;

    @Autowired
    private AnalysisState analysisState;

    @Autowired
    private DuckDbQueryService duckDbQueryService;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 批量上传数据文件（xlsx/xls/csv），转为 Parquet 并绑定到对话。
     * <p>
     * 如果已有文件，会被替换（物理删除 + 逻辑删除）。
     */
    @PostMapping("/upload")
    public BaseResponse<List<DataFileVO>> upload(
            @RequestPart("files") MultipartFile[] files,
            @RequestParam Long conversationId,
            @RequestParam(defaultValue = "true") boolean replaceIfExists,
            HttpServletRequest request) {
        if (files == null || files.length == 0)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "至少需要上传一个文件");
        if (conversationId == null || conversationId <= 0)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");

        RLock runLock = redissonClient.getLock(AgentLockKeys.GLOBAL_RUN_LOCK);
        boolean lockAcquired = false;
        try {
            // 空闲时持有运行锁完成替换，阻止任务在文件切换中途启动。
            // 运行中仍允许上传，但降级为追加，供下一轮显式选择后使用。
            lockAcquired = runLock.tryLock(0, TimeUnit.SECONDS);
            boolean deferReplacement = replaceIfExists && !lockAcquired;
            List<DataFile> dataFiles = fileConversionService.batchUpload(
                    files, conversationId, replaceIfExists && lockAcquired);

            if (lockAcquired) {
                // 空闲时新文件立即生效，清除旧工作索引。
                analysisState.resetByConversation(conversationId.toString());
            } else if (deferReplacement) {
                log.info("任务运行中，文件替换已延后到下一轮使用");
            }

            List<DataFileVO> vos = dataFiles.stream().map(this::toVO).collect(Collectors.toList());
            log.info("文件批量上传完成：{}个文件", dataFiles.size());
            return ResultUtils.success(vos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件批量上传失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件处理失败: " + e.getMessage());
        } finally {
            unlockQuietly(runLock, lockAcquired);
        }
    }

    /** 列出指定对话的可用数据文件 */
    @GetMapping("/list")
    public BaseResponse<List<DataFileVO>> listFiles(@RequestParam Long conversationId) {
        if (conversationId == null || conversationId <= 0)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");

        QueryWrapper<DataFile> qw = new QueryWrapper<>();
        qw.eq("conversationId", conversationId)
                .eq("status", "READY").orderByAsc("createTime");
        List<DataFileVO> vos = dataFileMapper.selectList(qw).stream()
                .map(this::toVO).collect(Collectors.toList());
        return ResultUtils.success(vos);
    }

    /** 删除单个文件 */
    @DeleteMapping("/{fileId}")
    public BaseResponse<Boolean> deleteFile(@PathVariable Long fileId) {
        RLock runLock = redissonClient.getLock(AgentLockKeys.GLOBAL_RUN_LOCK);
        boolean lockAcquired = false;
        try {
            lockAcquired = runLock.tryLock(0, TimeUnit.SECONDS);
            if (!lockAcquired) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "任务运行中，暂不能删除文件");
            }
            DataFile df = dataFileMapper.selectById(fileId);
            if (df == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "文件不存在");
            }
            // 先删除元数据，物理删除失败时由孤儿文件清理任务兜底。
            dataFileMapper.deleteById(fileId);
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(df.getStoragePath()));
            } catch (Exception e) {
                log.warn("物理文件删除失败：{}", df.getStoragePath());
            }
            analysisState.resetByConversation(df.getConversationId().toString());
            return ResultUtils.success(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙");
        } finally {
            unlockQuietly(runLock, lockAcquired);
        }
    }

    /**
     * 预览文件数据 — 分页查询 Parquet 文件的实际数据行。
     *
     * @param fileId 文件 ID
     * @param page   页码（从 1 开始，默认 1）
     * @param size   每页行数（默认 30，最大 200）
     */
    @GetMapping("/{fileId}/preview")
    public BaseResponse<FilePreviewVO> previewFile(
            @PathVariable Long fileId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int size) {
        DataFile df = dataFileMapper.selectById(fileId);
        if (df == null)
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "文件不存在");
        if (!"READY".equals(df.getStatus()))
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件尚未就绪");
        if (df.getStoragePath() == null || df.getViewName() == null)
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件数据路径异常");

        FilePreviewVO vo = duckDbQueryService.previewData(
                df.getStoragePath(), df.getViewName(), page, size);
        return ResultUtils.success(vo);
    }

    private DataFileVO toVO(DataFile df) {
        DataFileVO vo = new DataFileVO();
        vo.setId(df.getId());
        vo.setOriginalFilename(df.getOriginalFilename());
        vo.setFileSize(df.getFileSize());
        vo.setRowCount(df.getRowCount());
        vo.setColumnMeta(df.getColumnMeta());
        vo.setStatus(df.getStatus());
        vo.setCreateTime(df.getCreateTime());
        return vo;
    }

    private static void unlockQuietly(RLock lock, boolean lockAcquired) {
        if (!lockAcquired) return;
        try {
            if (lock.isLocked()) {
                lock.forceUnlock();
            }
        } catch (Exception ignored) {
        }
    }
}
