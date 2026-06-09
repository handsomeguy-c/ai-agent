package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ChatMessageMapper {
    int insert(ChatMessage chatMessage);

    ChatMessage selectById(String id);

    List<ChatMessage> selectBySessionId(String sessionId);

    List<ChatMessage> selectBySessionIdRecently(String sessionId, int limit);

    int deleteById(String id);

    int updateById(ChatMessage chatMessage);
}
