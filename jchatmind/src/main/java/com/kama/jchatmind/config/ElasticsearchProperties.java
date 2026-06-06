package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.elasticsearch")
public class ElasticsearchProperties {
    private boolean enabled = false;
    private String baseUrl = "http://localhost:9200";
    private String indexName = "jchatmind_chunks";
}
