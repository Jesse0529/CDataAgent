package com.AIBI.service.impl;

import com.AIBI.mapper.ConversationMessageMapper;
import com.AIBI.model.entity.ConversationMessage;
import com.AIBI.service.ConversationMessageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 对话消息服务实现
 */
@Service
public class ConversationMessageServiceImpl extends ServiceImpl<ConversationMessageMapper, ConversationMessage>
        implements ConversationMessageService {
}
