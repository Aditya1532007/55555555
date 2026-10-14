package com.seekora.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Optional cross-origin access for the API. Empty by default: the bundled UI is served by this same app, so no
 * CORS is needed. Set SEEKORA_CORS_ORIGINS (comma-separated) only if the UI is hosted from a different origin,
 * e.g. a dev server on http://localhost:5500.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String[] origins;

    public CorsConfig(@Value("${seekora.cors.allowed-origins:}") String allowedOrigins) {
        this.origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) return;
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept").allowCredentials(true).maxAge(3600);
    }
}
