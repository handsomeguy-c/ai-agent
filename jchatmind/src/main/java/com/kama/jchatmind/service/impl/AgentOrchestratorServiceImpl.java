package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.AgentOrchestratorService;
import com.kama.jchatmind.service.ExecutorService;
import com.kama.jchatmind.service.PlannerService;
import com.kama.jchatmind.service.VerifierService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class AgentOrchestratorServiceImpl implements AgentOrchestratorService {

    private final PlannerService plannerService;
    private final ExecutorService executorService;
    private final VerifierService verifierService;

    @Override
    public String run(String userInput) {
        List<String> plan = plannerService.plan(userInput);
        String evidence = executorService.executePlan(plan);
        boolean verified = verifierService.verify(userInput, evidence);
        return verified ? evidence : "未能完成可信校验";
    }
}
