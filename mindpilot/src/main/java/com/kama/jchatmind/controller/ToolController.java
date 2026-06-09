package com.kama.jchatmind.controller;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.dto.ToolCallResponseDTO;
import com.kama.jchatmind.model.dto.ToolSchemaDTO;
import com.kama.jchatmind.model.request.ToolCallRequest;
import com.kama.jchatmind.service.ToolFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ToolController {

    private final ToolFacadeService toolFacadeService;

    // 给前端提供的可选的工具列表
    @GetMapping("/tools")
    public ApiResponse<List<Tool>> getOptionalTools() {
        return ApiResponse.success(toolFacadeService.getOptionalTools());
    }

    // MCP 风格工具发现：返回模型可注入上下文的 schema
    @GetMapping("/tools/list")
    public ApiResponse<List<ToolSchemaDTO>> listToolSchemas() {
        return ApiResponse.success(toolFacadeService.listToolSchemas());
    }

    // MCP 风格工具调用：由系统统一接管 tool call，封装参数、调用和错误。
    @PostMapping("/tools/call")
    public ApiResponse<ToolCallResponseDTO> callTool(@RequestBody ToolCallRequest request) {
        return ApiResponse.success(toolFacadeService.callTool(request.getName(), request.getArguments()));
    }
}
