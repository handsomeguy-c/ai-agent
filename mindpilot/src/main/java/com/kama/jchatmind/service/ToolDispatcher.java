package com.kama.jchatmind.service;

import com.kama.jchatmind.model.tool.ToolInvocation;
import com.kama.jchatmind.model.tool.ToolObservation;

public interface ToolDispatcher {
    ToolObservation dispatch(ToolInvocation invocation);
}
