package com.AIBI.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户上传的数据文件实体。
 * 文件上传后转为 Parquet 格式存储于本地磁盘，元信息记录在 H2 中。
 */
@Data
@TableName("data_file")
public class DataFile implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 原始文件名 */
    private String originalFilename;

    /** 存储路径（Parquet 文件绝对路径） */
    private String storagePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 数据行数 */
    private Integer rowCount;

    /** 列信息 JSON：[{"name":"col1","type":"VARCHAR","sample":"abc"}] */
    private String columnMeta;

    /** DuckDB 注册的视图名，如 data_user1_file12345 */
    private String viewName;

    /** 文件状态：READY / CONVERTING / FAILED */
    private String status;

    /** 关联的对话 ID（可选，绑定到对话） */
    private Long conversationId;

    /** 文件内容哈希（SHA256），用于去重：SHA256(originalFilename + fileBytes) */
    private String contentHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
