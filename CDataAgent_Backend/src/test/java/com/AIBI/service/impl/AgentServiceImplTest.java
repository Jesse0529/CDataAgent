package com.AIBI.service.impl;

import com.AIBI.model.entity.Conversation;
import com.AIBI.service.ConversationMessageService;
import com.AIBI.service.ConversationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplTest {

    @Test
    void deleteMessagesOnlyRemovesPersistedMessages() throws Exception {
        AgentServiceImpl service = new AgentServiceImpl();
        ConversationService conversationService = mock(ConversationService.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        Conversation conversation = new Conversation();
        conversation.setId(1L);
        when(conversationService.getById(1L)).thenReturn(conversation);
        setField(service, "conversationService", conversationService);
        setField(service, "conversationMessageService", messageService);

        service.deleteMessages(1L);

        verify(messageService).remove(any());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
