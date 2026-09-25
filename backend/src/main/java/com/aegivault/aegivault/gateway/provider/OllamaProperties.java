package com.aegivault.aegivault.gateway.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the local Ollama provider: base URL plus
 * connection and read timeouts only. No API keys, no cloud
 * credentials — Ollama is a local HTTP server. All values carry safe
 * localhost defaults so a fresh checkout works without any
 * configuration; see {@code application-example.properties}.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.gateway.ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(60);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
