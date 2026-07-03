package com.AIBI.model.vo;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 对话消息视图
 */
@Data
public class MessageVO implements Serializable {

    /**
     * 消息ID
     */
    private Long id;

    /**
     * 角色：user / assistant
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 关联文件 JSON：[{"id":"1","name":"a.xlsx"}]
     */
    private String fileAttachments;

    /**
     * 图表配置 JSON（ECharts option），仅 assistant 消息可能有值
     */
    private String chartOption;

    /**
     * 本轮消耗的 token 数（来自 API 精确响应）
     */
    private Integer tokenUsage;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
