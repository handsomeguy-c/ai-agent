package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.SessionSummary;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextService;
import com.kama.jchatmind.service.SessionSummaryService;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ConversationContextServiceImpl implements ConversationContextService {

    private static final int SUMMARY_TRIGGER_MESSAGE_COUNT = 20;

    private final ChatMessageFacadeService chatMessageFacadeService;
    private final SessionSummaryService sessionSummaryService;

    @Override
    public List<Message> loadContextMessages(String chatSessionId, int recentLimit) {
        maybeRefreshSummary(chatSessionId);
        List<Message> memory = new ArrayList<>();
        SessionSummary summary = sessionSummaryService.getLatestSummary(chatSessionId);
        if (summary != null && StringUtils.hasLength(summary.getSummary())) {
            memory.add(new SystemMessage("以下是更早对话的压缩摘要，请作为当前会话背景使用：\n" + summary.getSummary()));
        }

        List<ChatMessageDTO> recentMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, recentLimit);
        for (ChatMessageDTO chatMessageDTO : recentMessages) {
            Message message = toSpringMessage(chatMessageDTO);
            if (message != null) {
                memory.add(message);
            }
        }
        return memory;
    }

    private void maybeRefreshSummary(String chatSessionId) {
        List<ChatMessageDTO> probe = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, SUMMARY_TRIGGER_MESSAGE_COUNT + 1);
        if (probe.size() > SUMMARY_TRIGGER_MESSAGE_COUNT) {
            sessionSummaryService.rebuildSummary(chatSessionId);
        }
    }

    private Message toSpringMessage(ChatMessageDTO chatMessageDTO) {
        switch (chatMessageDTO.getRole()) {
            case SYSTEM:
                return StringUtils.hasLength(chatMessageDTO.getContent()) ? new SystemMessage(chatMessageDTO.getContent()) : null;
            case USER:
                return StringUtils.hasLength(chatMessageDTO.getContent()) ? new UserMessage(chatMessageDTO.getContent()) : null;
            case ASSISTANT:
                return StringUtils.hasLength(chatMessageDTO.getContent()) ? new AssistantMessage(chatMessageDTO.getContent()) : null;
            case TOOL:
                if (chatMessageDTO.getMetadata() == null || chatMessageDTO.getMetadata().getToolResponse() == null) {
                    return null;
                }
                return new SystemMessage("""
                        以下是历史工具调用结果，仅作为会话背景参考，不代表本轮已经调用了工具：
                        工具：%s
                        结果：%s
                        """.formatted(
                        chatMessageDTO.getMetadata().getToolResponse().name(),
                        chatMessageDTO.getMetadata().getToolResponse().responseData()
                ));
            default:
                return null;
        }
    }
}
