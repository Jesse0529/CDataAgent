package com.AIBI.config;

import org.springframework.stereotype.Component;

/**
 * 当前对话 ID 的线程级上下文 — 用于 ChatModel 装饰器透明获取 conversationId。
 * <p>
 * 在 AgentServiceImpl 进入流管道前 set(cid)，管道结束后 remove()。
 * TokenRecordingChatModel 在记录 token 消耗时通过 get() 获取当前对话 ID。
 */
@Component
public class ConversationContext {

    private final ThreadLocal<Long> holder = new ThreadLocal<>();

    public void set(Long conversationId) {
        holder.set(conversationId);
    }

    public Long get() {
        return holder.get();
    }

    public void remove() {
        holder.remove();
    }
}
