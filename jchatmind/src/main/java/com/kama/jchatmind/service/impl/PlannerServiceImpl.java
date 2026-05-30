package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.PlannerService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlannerServiceImpl implements PlannerService {
    @Override
    public List<String> plan(String userInput) {
        return List.of("理解用户问题", "检索必要的知识和记忆", "生成并校验回答");
    }
}
