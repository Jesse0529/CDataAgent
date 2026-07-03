package com.AIBI.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据文件视图对象。
 */
@Data
public class DataFileVO implements Serializable {

    private Long id;
    private String originalFilename;
    private Long fileSize;
    private Integer rowCount;
    private String columnMeta;
    private String status;
    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
