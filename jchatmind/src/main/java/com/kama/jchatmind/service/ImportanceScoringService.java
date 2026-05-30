package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ChatMessageDTO;

public interface ImportanceScoringService {
    double score(ChatMessageDTO message);

    String decideMemoryAction(double score);
}
