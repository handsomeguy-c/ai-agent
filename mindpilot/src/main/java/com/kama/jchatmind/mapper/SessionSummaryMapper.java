package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.SessionSummary;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionSummaryMapper {
    int insert(SessionSummary sessionSummary);

    SessionSummary selectLatestBySessionId(String sessionId);

    int deleteBySessionId(String sessionId);
}
