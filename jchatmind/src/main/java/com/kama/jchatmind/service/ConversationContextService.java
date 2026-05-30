package com.kama.jchatmind.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface ConversationContextService {
    List<Message> loadContextMessages(String chatSessionId, int recentLimit);
}
