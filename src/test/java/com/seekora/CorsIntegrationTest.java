package com.seekora;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies the opt-in CORS allow-list used when the UI is served from another origin (e.g. a dev server). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seekora-cors-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "seekora.cors.allowed-origins=http://localhost:5500"
})
@AutoConfigureMockMvc
class CorsIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void allowedOriginPassesPreflightAndMutation() throws Exception {
        mvc.perform(options("/api/v1/chat").header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "http://localhost:5500").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/status")).andExpect(header().string("Content-Security-Policy", containsString("connect-src 'self' http://localhost:5500")));
    }

    @Test void otherOriginsStillBlocked() throws Exception {
        mvc.perform(options("/api/v1/chat").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
