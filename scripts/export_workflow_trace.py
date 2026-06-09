#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


def load_payload(base_url: str, session_id: str) -> dict:
    url = f"{base_url.rstrip('/')}/api/chat-messages/session/{session_id}"
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise SystemExit(f"Failed to fetch workflow trace from {url}: {exc}") from exc


def find_messages(payload: dict) -> list:
    data = payload.get("data") if isinstance(payload, dict) else None
    if isinstance(data, dict):
        messages = data.get("chatMessages")
        if isinstance(messages, list):
            return messages
    messages = payload.get("chatMessages") if isinstance(payload, dict) else None
    return messages if isinstance(messages, list) else []


def compact_plan(plan: dict) -> dict:
    steps = plan.get("steps") or []
    return {
        "planId": plan.get("planId"),
        "goal": plan.get("goal"),
        "version": plan.get("version"),
        "createdBy": plan.get("createdBy"),
        "replanReason": plan.get("replanReason"),
        "steps": [
            {
                "stepId": step.get("stepId"),
                "stepType": step.get("stepType"),
                "expert": step.get("expert"),
                "toolName": step.get("toolName"),
                "status": step.get("status"),
                "description": step.get("description"),
            }
            for step in steps
        ],
    }


def compact_step_result(result: dict) -> dict:
    metadata = result.get("metadata") or {}
    return {
        "stepId": result.get("stepId"),
        "stepType": result.get("stepType"),
        "expert": result.get("expert"),
        "status": result.get("status"),
        "durationMs": result.get("durationMs"),
        "errorMessage": result.get("errorMessage"),
        "observation": result.get("observation"),
        "metadata": {
            "delegatedBy": metadata.get("delegatedBy"),
            "expertProfile": metadata.get("expertProfile"),
            "routingPolicy": metadata.get("routingPolicy"),
            "toolName": metadata.get("toolName"),
            "hasFailedTool": metadata.get("hasFailedTool"),
            "insufficientRetrieval": metadata.get("insufficientRetrieval"),
        },
    }


def compact_workflow_state(state: dict) -> dict:
    context = state.get("contextState") or {}
    scratchpad = context.get("scratchpad") or {}
    return {
        "executionId": state.get("executionId"),
        "agentId": state.get("agentId"),
        "sessionId": state.get("sessionId"),
        "executionMode": state.get("executionMode"),
        "status": state.get("status"),
        "currentStepIndex": state.get("currentStepIndex"),
        "toolCallCount": state.get("toolCallCount"),
        "maxToolCalls": state.get("maxToolCalls"),
        "replanCount": state.get("replanCount"),
        "maxReplans": state.get("maxReplans"),
        "terminationReason": state.get("terminationReason"),
        "finalAnswer": state.get("finalAnswer"),
        "contextState": {
            "userInput": context.get("userInput"),
            "availableToolNames": context.get("availableToolNames"),
            "availableKnowledgeBaseIds": context.get("availableKnowledgeBaseIds"),
            "citationCount": len(context.get("citations") or []),
            "scratchpad": {
                "activeExpert": scratchpad.get("activeExpert"),
                "activeStepType": scratchpad.get("activeStepType"),
                "routingPolicy": scratchpad.get("routingPolicy"),
                "currentRound": scratchpad.get("currentRound"),
                "maxRounds": scratchpad.get("maxRounds"),
            },
        },
        "stepResultCount": len(state.get("stepResults") or []),
    }


def export_trace(messages: list) -> dict:
    plans = []
    step_results = []
    workflow_states = []

    for message in messages:
        metadata = message.get("metadata") or {}
        if isinstance(metadata.get("plan"), dict):
            plans.append({
                "messageId": message.get("id"),
                "createdAt": message.get("createdAt"),
                "content": message.get("content"),
                "plan": compact_plan(metadata["plan"]),
            })
        if isinstance(metadata.get("stepResult"), dict):
            step_results.append({
                "messageId": message.get("id"),
                "createdAt": message.get("createdAt"),
                "content": message.get("content"),
                "stepResult": compact_step_result(metadata["stepResult"]),
            })
        if isinstance(metadata.get("workflowState"), dict):
            workflow_states.append({
                "messageId": message.get("id"),
                "createdAt": message.get("createdAt"),
                "workflowState": compact_workflow_state(metadata["workflowState"]),
            })

    return {
        "messageCount": len(messages),
        "planTraceCount": len(plans),
        "stepResultTraceCount": len(step_results),
        "workflowStateTraceCount": len(workflow_states),
        "plans": plans,
        "stepResults": step_results,
        "latestWorkflowState": workflow_states[-1]["workflowState"] if workflow_states else None,
        "workflowStates": workflow_states,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Export MindPilot Agent workflow trace from chat message metadata.")
    parser.add_argument("--base-url", default="http://localhost:8083", help="Backend base URL.")
    parser.add_argument("--session-id", required=True, help="Chat session ID to inspect.")
    parser.add_argument("--out", default="", help="Output JSON path. Defaults to stdout.")
    args = parser.parse_args()

    payload = load_payload(args.base_url, args.session_id)
    trace = export_trace(find_messages(payload))

    text = json.dumps(trace, ensure_ascii=False, indent=2)
    if args.out:
        path = Path(args.out)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")
        print(f"Workflow trace exported to {path}")
        print(f"plans={trace['planTraceCount']} stepResults={trace['stepResultTraceCount']} workflowStates={trace['workflowStateTraceCount']}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
