package com.AIBI.service.impl;

import com.AIBI.mapper.ConversationMapper;
import com.AIBI.model.entity.Conversation;
import com.AIBI.service.ConversationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 对话服务实现
 */
@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {
}
