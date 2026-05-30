package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ToolExecutionLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ToolExecutionLogMapper {
    int insert(ToolExecutionLog log);
}
