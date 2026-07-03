package com.AIBI.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 对话表
 * @TableName conversation
 */
@TableName(value = "conversation")
@Data
public class Conversation implements Serializable {
    /**
     * 对话ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 对话标题（取自首条用户消息前100字）
     */
    private String title;

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
