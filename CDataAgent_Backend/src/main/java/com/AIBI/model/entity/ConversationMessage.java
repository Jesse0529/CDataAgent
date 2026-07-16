package com.AIBI.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 对话消息表
 * @TableName conversation_message
 */
@TableName(value = "conversation_message")
@Data
public class ConversationMessage implements Serializable {
    /**
     * 消息ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属对话ID
     */
    private Long conversationId;

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
     * 分析结论（从 content 中提取的纯结论文本，不含推理过程）
     */
    private String conclusion;

    /**
     * 本轮消耗的 token 数（精确值，来自 API 响应）。
     * user 消息记录本轮输入 token，assistant 消息记录本轮输出 token，
     * 汇总可用于对话级成本追踪。
     */
    private Integer tokenUsage;

    /**
     * RenderDocument v1 JSON（新协议的权威展示文档）。
     * 仅 assistant 消息可能有值；旧消息和旧协议消息为 null。
     */
    private String renderDocument;

    /**
     * 渲染协议版本（renderDocument 对应的版本号）。
     * 当前为 1；旧消息为 null。
     */
    private Integer renderVersion;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
