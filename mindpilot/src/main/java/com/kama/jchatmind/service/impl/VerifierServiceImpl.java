package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.VerifierService;
import org.springframework.stereotype.Service;

@Service
public class VerifierServiceImpl implements VerifierService {
    @Override
    public boolean verify(String answer, String evidence) {
        return answer != null && !answer.isBlank() && evidence != null && !evidence.isBlank();
    }
}
