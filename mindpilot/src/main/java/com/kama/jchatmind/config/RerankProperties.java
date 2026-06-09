package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rerank")
public class RerankProperties {
    private boolean enabled = false;
    private String endpoint = "http://localhost:8081/rerank";
    private String model = "BAAI/bge-reranker-v2-m3";
}
