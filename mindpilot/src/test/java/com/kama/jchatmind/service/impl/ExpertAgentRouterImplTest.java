package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.workflow.ExpertAgentRoute;
import com.kama.jchatmind.model.workflow.PlanStep;
import com.kama.jchatmind.model.workflow.StepType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertAgentRouterImplTest {

    private final ExpertAgentRouterImpl router = new ExpertAgentRouterImpl();

    @Test
    void shouldRouteRetrievalStepToKnowledgeExpert() {
        ExpertAgentRoute route = router.route(PlanStep.builder()
                .stepType(StepType.RETRIEVAL)
                .toolName("rag.hybrid_search")
                .build());

        assertThat(route.getExpert()).isEqualTo("KnowledgeExpert");
        assertThat(route.getRoutingPolicy()).isEqualTo("route_to_rag_or_knowledge_tool");
        assertThat(route.isRequiresToolCall()).isTrue();
        assertThat(route.isReadOnly()).isTrue();
        assertThat(route.toMetadata())
                .containsEntry("delegatedBy", "CentralDispatcher")
                .containsEntry("expert", "KnowledgeExpert")
                .containsEntry("recommendedTool", "rag.hybrid_search");
    }

    @Test
    void shouldRouteMemoryWriteAsNonReadOnlyMemoryExpert() {
        ExpertAgentRoute route = router.route(PlanStep.builder()
                .stepType(StepType.MEMORY_WRITE)
                .toolName("memory.save_long_term")
                .build());

        assertThat(route.getExpert()).isEqualTo("MemoryExpert");
        assertThat(route.getRoutingPolicy()).isEqualTo("route_to_memory_service_or_memory_mcp");
        assertThat(route.isRequiresToolCall()).isTrue();
        assertThat(route.isReadOnly()).isFalse();
    }

    @Test
    void shouldKeepPlannerProvidedExpertWhenPresent() {
        ExpertAgentRoute route = router.route(PlanStep.builder()
                .stepType(StepType.TOOL_CALL)
                .expert("CustomToolExpert")
                .build());

        assertThat(route.getExpert()).isEqualTo("CustomToolExpert");
        assertThat(route.getRoutingPolicy()).isEqualTo("route_to_tool_dispatcher");
    }

    @Test
    void shouldFallbackNullStepToDirectAnswer() {
        ExpertAgentRoute route = router.route(null);

        assertThat(route.getExpert()).isEqualTo("SynthesisExpert");
        assertThat(route.getStepType()).isEqualTo(StepType.DIRECT_ANSWER);
        assertThat(route.isRequiresToolCall()).isFalse();
    }
}
