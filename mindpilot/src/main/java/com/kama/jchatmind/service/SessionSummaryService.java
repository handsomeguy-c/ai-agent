package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.SessionSummary;

public interface SessionSummaryService {
    SessionSummary getLatestSummary(String sessionId);

    SessionSummary rebuildSummary(String sessionId);
}
