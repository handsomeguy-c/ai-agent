package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.service.MemoryService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;
    private final MemoryService memoryService;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            memoryService.captureTurn(event.getAgentId(), event.getSessionId(), event.getUserInput());
        } catch (Exception e) {
            log.warn("抽取记忆失败，继续执行 Agent: {}", e.getMessage());
        }
        // 创建一个 Agent 实例处理聊天事件
        JChatMind jChatMind = jChatMindFactory.create(event.getAgentId(), event.getSessionId(), event.getUserInput());
        jChatMind.run();
    }
}
