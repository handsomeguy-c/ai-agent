package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.ToolExecutionLog;

public interface ToolExecutionLogService {
    void record(ToolExecutionLog log);
}
