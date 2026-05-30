package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.ExecutorService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExecutorServiceImpl implements ExecutorService {
    @Override
    public String executePlan(List<String> plan) {
        return String.join(" -> ", plan);
    }
}
