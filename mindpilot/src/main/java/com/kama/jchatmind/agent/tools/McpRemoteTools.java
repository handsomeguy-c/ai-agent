package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.McpProperties;
import com.kama.jchatmind.model.dto.ToolSchemaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class McpRemoteTools implements Tool {

    private final McpProperties mcpProperties;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public McpRemoteTools(
            McpProperties mcpProperties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.mcpProperties = mcpProperties;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public String getName() {
        return "McpRemoteTool";
    }

    @Override
    public String getDescription() {
        return "远程 MCP Client 适配器，通过 initialize、tools/list、tools/call 调用外部 MCP Server。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "McpRemoteTool",
            description = "调用远程 MCP Server 工具。serverName 为配置的 MCP 服务名，toolName 为远程工具名，argumentsJson 为 JSON 对象字符串。"
    )
    public String callRemoteTool(
            @ToolParam(description = "配置中的 MCP Server 名称") String serverName,
            @ToolParam(description = "远程 MCP 工具名称") String toolName,
            @ToolParam(description = "JSON 对象字符串形式的工具参数，例如 {\"city\":\"Shanghai\"}") String argumentsJson
    ) {
        if (!mcpProperties.isEnabled()) {
            return "MCP remote tools are disabled. Set app.mcp.enabled=true to enable them.";
        }
        McpProperties.Server server = findServer(serverName);
        if (server == null) {
            return "MCP server not found: " + serverName;
        }
        if (!StringUtils.hasText(toolName)) {
            return "MCP toolName cannot be empty.";
        }

        initialize(server);
        Map<String, Object> arguments = parseArguments(argumentsJson);
        Map<String, Object> response = callJsonRpc(server, "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments
        ));
        return toCompactJson(response);
    }

    public List<ToolSchemaDTO> listRemoteToolSchemas() {
        if (!mcpProperties.isEnabled()) {
            return List.of();
        }
        List<ToolSchemaDTO> schemas = new ArrayList<>();
        for (McpProperties.Server server : mcpProperties.getServers()) {
            if (!isUsable(server)) {
                continue;
            }
            try {
                initialize(server);
                Map<String, Object> response = callJsonRpc(server, "tools/list", Map.of());
                JsonNode toolsNode = objectMapper.valueToTree(response).at("/result/tools");
                if (!toolsNode.isArray()) {
                    continue;
                }
                for (JsonNode toolNode : toolsNode) {
                    String remoteName = toolNode.path("name").asText();
                    if (!StringUtils.hasText(remoteName)) {
                        continue;
                    }
                    JsonNode metaNode = toolNode.path("_meta");
                    schemas.add(ToolSchemaDTO.builder()
                            .name("mcp." + server.getName() + "." + remoteName)
                            .description(toolNode.path("description").asText(server.getDescription()))
                            .type(ToolType.OPTIONAL)
                            .source("MCP:" + server.getName() + buildMetaSuffix(metaNode))
                            .sourceType(com.kama.jchatmind.model.tool.ToolSourceType.MCP)
                            .inputSchema(toolNode.path("inputSchema").isMissingNode() ? null : toolNode.path("inputSchema").toString())
                            .timeoutMs(10_000L)
                            .maxRetries(1)
                            .permissionLevel(com.kama.jchatmind.model.tool.PermissionLevel.AGENT_ALLOWED)
                            .returnDirect(false)
                            .build());
                }
            } catch (Exception e) {
                log.warn("MCP tools/list failed: server={}, error={}", server.getName(), e.getMessage());
            }
        }
        return schemas;
    }

    private String buildMetaSuffix(JsonNode metaNode) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (metaNode.has("capability")) {
            parts.add("capability=" + metaNode.path("capability").asText());
        }
        if (metaNode.has("readOnlyHint")) {
            parts.add("readOnly=" + metaNode.path("readOnlyHint").asBoolean());
        }
        if (metaNode.has("memoryLayer")) {
            parts.add("memoryLayer=" + metaNode.path("memoryLayer").asText());
        }
        return parts.isEmpty() ? "" : " [" + String.join(",", parts) + "]";
    }

    private void initialize(McpProperties.Server server) {
        Map<String, Object> initResponse = callJsonRpc(server, "initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("tools", Map.of()),
                "clientInfo", Map.of(
                        "name", "jchatmind-mcp-client",
                        "version", "0.1.0"
                )
        ));
        if (initResponse.containsKey("error")) {
            throw new IllegalStateException("MCP initialize failed: " + toCompactJson(initResponse));
        }
        try {
            callJsonRpc(server, "notifications/initialized", Map.of());
        } catch (Exception e) {
            log.debug("MCP initialized notification ignored: server={}, error={}", server.getName(), e.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("raw", argumentsJson);
        }
    }

    private Map<String, Object> callJsonRpc(McpProperties.Server server, String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", "jchatmind-" + Instant.now().toEpochMilli());
        request.put("method", method);
        request.put("params", params);

        String response = webClientBuilder.build()
                .post()
                .uri(server.getEndpoint())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (!StringUtils.hasText(response)) {
            return Map.of("status", "EMPTY_RESPONSE");
        }
        try {
            return objectMapper.readValue(response, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("raw", response);
        }
    }

    private McpProperties.Server findServer(String serverName) {
        return mcpProperties.getServers().stream()
                .filter(this::isUsable)
                .filter(server -> server.getName().equals(serverName))
                .findFirst()
                .orElse(null);
    }

    private boolean isUsable(McpProperties.Server server) {
        return server != null && StringUtils.hasText(server.getName()) && StringUtils.hasText(server.getEndpoint());
    }

    private String toCompactJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}
