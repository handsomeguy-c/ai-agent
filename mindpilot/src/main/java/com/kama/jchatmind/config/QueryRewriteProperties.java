package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.query-rewrite")
public class QueryRewriteProperties {
    private boolean llmEnabled = true;
    private String model = "deepseek-chat";
}
