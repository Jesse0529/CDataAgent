package com.AIBI.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 文件数据预览视图 — 分页返回 Parquet 文件的数据行。
 */
@Data
public class FilePreviewVO implements Serializable {

    /** 列名列表 */
    private List<String> headers;

    /** 数据行（每行是一个 Object 列表，与 headers 顺序对应） */
    private List<List<Object>> rows;

    /** 文件总行数 */
    private Integer totalRows;

    /** 当前页码（从 1 开始） */
    private Integer page;

    /** 每页行数 */
    private Integer pageSize;

    /** 是否有更多数据 */
    private Boolean hasMore;

    private static final long serialVersionUID = 1L;
}
