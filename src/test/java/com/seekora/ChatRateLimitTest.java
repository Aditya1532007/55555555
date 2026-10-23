package com.seekora;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekora.ai.AnswerGenerator;
import com.seekora.web.WebResearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seekora-limit-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "seekora.limits.chat-per-account-per-hour=2"
})
@AutoConfigureMockMvc
class ChatRateLimitTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AnswerGenerator ai;
    @MockitoBean WebResearchService research;

    private MockHttpSession register(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Limit Tester", "email", email, "password", "correct horse battery staple"))))
                .andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
    }

    @Test void eachAccountHasItsOwnHourlyQuestionBudget() throws Exception {
        when(ai.isConfigured()).thenReturn(true);
        when(ai.modelName()).thenReturn("stub");
        when(ai.generate(anyString(), anyList(), anyList())).thenReturn("answer");
        when(research.research(anyString())).thenReturn(new WebResearchService.ResearchResult("s", List.of()));
        MockHttpSession a = register("limit-a@example.org"), b = register("limit-b@example.org");
        String body = json.writeValueAsString(Map.of("message", "What is a question?"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/chat").session(a).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/chat").session(a).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/chat").session(b).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
