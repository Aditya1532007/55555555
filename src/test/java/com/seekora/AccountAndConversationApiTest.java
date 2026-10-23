package com.seekora;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seekora-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccountAndConversationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void accountSessionAndConversationDataWorkEndToEnd() throws Exception {
        String email = "api-test@example.org";
        String password = "correct horse battery staple";
        MvcResult register = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "API Tester", "email", email, "password", password))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id", not(emptyString()))).andReturn();
        MockHttpSession session = (MockHttpSession) register.getRequest().getSession(false);

        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        MvcResult created = mvc.perform(post("/api/v1/conversations").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Research notes\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Research notes"))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsByteArray()).get("id").asText();

        mvc.perform(get("/api/v1/conversations").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/conversations/{id}", id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Research notes"));
        mvc.perform(delete("/api/v1/conversations/{id}", id).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/conversations").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void invalidJsonAndValidationReturnClientErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("invalid JSON")));
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/chat").session(signedInSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").exists());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    private MockHttpSession signedInSession() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("name", "Valid Tester", "email", "valid-" + java.util.UUID.randomUUID() + "@example.org",
                                "password", "correct horse battery staple"))))
                .andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }
}
