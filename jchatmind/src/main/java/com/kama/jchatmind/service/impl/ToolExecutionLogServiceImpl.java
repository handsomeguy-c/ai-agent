package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ToolExecutionLogMapper;
import com.kama.jchatmind.model.entity.ToolExecutionLog;
import com.kama.jchatmind.service.ToolExecutionLogService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
public class ToolExecutionLogServiceImpl implements ToolExecutionLogService {

    private final ToolExecutionLogMapper toolExecutionLogMapper;

    @Override
    public void record(ToolExecutionLog log) {
        log.setCreatedAt(log.getCreatedAt() == null ? LocalDateTime.now() : log.getCreatedAt());
        log.setRetryCount(log.getRetryCount() == null ? 0 : log.getRetryCount());
        toolExecutionLogMapper.insert(log);
    }
}
