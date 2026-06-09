package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ImportanceScoringService;
import org.springframework.stereotype.Service;

@Service
public class ImportanceScoringServiceImpl implements ImportanceScoringService {

    @Override
    public double score(ChatMessageDTO message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return 0.0;
        }
        String content = message.getContent();
        double score = Math.min(0.45, content.length() / 1200.0);
        if (content.contains("记住") || content.contains("偏好") || content.contains("以后")) {
            score += 0.35;
        }
        if (content.contains("项目") || content.contains("数据库") || content.contains("错误") || content.contains("方案")) {
            score += 0.2;
        }
        if (message.getRole() == ChatMessageDTO.RoleType.TOOL) {
            score += 0.1;
        }
        return Math.min(1.0, score);
    }

    @Override
    public String decideMemoryAction(double score) {
        if (score >= 0.8) {
            return "LONG_TERM";
        }
        if (score >= 0.6) {
            return "SESSION_SUMMARY";
        }
        if (score >= 0.3) {
            return "CURRENT_CONTEXT";
        }
        return "DISCARD";
    }
}
