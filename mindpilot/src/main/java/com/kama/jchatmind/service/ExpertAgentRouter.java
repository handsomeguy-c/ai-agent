package com.kama.jchatmind.service;

import com.kama.jchatmind.model.workflow.ExpertAgentRoute;
import com.kama.jchatmind.model.workflow.PlanStep;

public interface ExpertAgentRouter {
    ExpertAgentRoute route(PlanStep planStep);
}
