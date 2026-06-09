package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {
    private boolean enabled = false;
    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {
        private String name;
        private String endpoint;
        private String description;
    }
}
