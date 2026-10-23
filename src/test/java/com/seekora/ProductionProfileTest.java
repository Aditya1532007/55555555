package com.seekora;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the production settings that protect user data. */
class ProductionProfileTest {
    private static String prop(String file, String key) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) return String.valueOf(value);
        }
        return null;
    }

    @Test void productionUsesPostgresSecureCookiesAndNeverWipesData() throws Exception {
        String url = prop("application-prod.yml", "spring.datasource.url");
        assertTrue(url.contains("jdbc:postgresql://"), url);
        assertFalse(url.contains("h2"), url);
        String ddl = prop("application-prod.yml", "spring.jpa.hibernate.ddl-auto");
        assertTrue(ddl.contains(":update}"), ddl);
        assertFalse(ddl.contains("create"), "production must never create/drop the schema: " + ddl);
        assertTrue(prop("application-prod.yml", "server.servlet.session.cookie.secure").contains(":true}"));
        assertTrue(prop("application-prod.yml", "seekora.ai.required").contains(":true}"));
        assertNotNull(prop("application-prod.yml", "spring.datasource.password"), "database password must come from the environment");
        assertEquals("false", prop("application.yml", "spring.h2.console.enabled"), "the H2 console must never be exposed");
        assertTrue(prop("application.yml", "server.servlet.session.timeout").contains("8h"));
    }

    @Test void noSecretsAreCommittedInConfiguration() throws Exception {
        for (String file : List.of("application.yml", "application-prod.yml")) {
            String text = new String(new ClassPathResource(file).getInputStream().readAllBytes());
            assertFalse(text.matches("(?s).*(sk-[A-Za-z0-9]{20,}|gsk_[A-Za-z0-9]{20,}).*"), file + " must not contain an API key");
        }
        assertEquals("${SEEKORA_AI_API_KEY:}", prop("application.yml", "seekora.ai.api-key"));
    }

    /** The Render Blueprint supplies host/port/name/user/password separately; the URL must be assembled from them. */
    @Test void databaseUrlIsAssembledFromRendersSeparateVariables() throws Exception {
        org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("render", java.util.Map.of(
                "SEEKORA_DB_HOST", "dpg-abc123-a", "SEEKORA_DB_NAME", "seekora")));
        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/seekora",
                env.resolvePlaceholders(prop("application-prod.yml", "spring.datasource.url")));
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("override", java.util.Map.of(
                "SEEKORA_DB_URL", "jdbc:postgresql://example:6543/other?sslmode=require")));
        assertEquals("jdbc:postgresql://example:6543/other?sslmode=require",
                env.resolvePlaceholders(prop("application-prod.yml", "spring.datasource.url")));
    }
}
