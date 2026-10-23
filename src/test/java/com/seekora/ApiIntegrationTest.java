package com.seekora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekora.ai.AiProviderException;
import com.seekora.ai.AnswerGenerator;
import com.seekora.web.ResearchException;
import com.seekora.web.WebResearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real HTTP API (controllers, filters, JPA on H2). Only the AI provider and the live web are stubbed. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seekora-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AnswerGenerator ai;
    @MockitoBean WebResearchService research;

    private static final WebResearchService.ResearchSource SRC =
            new WebResearchService.ResearchSource("Tides - Example", "https://example.org/tides", "Tides are caused by the gravity of the Moon and Sun acting on the oceans.");

    @BeforeEach void stubs() {
        when(ai.isConfigured()).thenReturn(true);
        when(ai.modelName()).thenReturn("stub-model");
        when(ai.providerLabel()).thenReturn("stub.local");
        when(research.research(anyString())).thenReturn(new WebResearchService.ResearchResult("local summary", List.of(SRC)));
    }

    private String chatBody(String message, String conversationId) throws Exception {
        return json.writeValueAsString(conversationId == null ? Map.of("message", message) : Map.of("message", message, "conversationId", conversationId));
    }

    private JsonNode chat(MockHttpSession session, String message, String conversationId) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/chat").session(session).contentType(MediaType.APPLICATION_JSON).content(chatBody(message, conversationId)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsByteArray());
    }

    /** A fresh signed-in session; every protected endpoint now needs one. */
    private MockHttpSession signedIn() throws Exception { return register("user-" + UUID.randomUUID() + "@example.org"); }

    private MockHttpSession register(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Tester", "email", email, "password", "correct horse battery staple"))))
                .andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test void chatReturnsAModelAnswerWithSourcesAndPersistsFollowUps() throws Exception {
        when(ai.generate(eq("What causes tides?"), anyList(), anyList())).thenReturn("Tides come from the **Moon's** gravity [1].");
        MockHttpSession session = signedIn();

        JsonNode first = chat(session, "What causes tides?", null);
        assertEquals("ai", first.get("answerMode").asText());
        assertEquals("used", first.get("searchStatus").asText());
        assertEquals("Tides come from the **Moon's** gravity [1].", first.get("assistantMessage").get("content").asText());
        assertEquals("https://example.org/tides", first.get("sources").get(0).get("url").asText());
        String id = first.get("conversationId").asText();

        when(ai.generate(eq("and the sun?"), anyList(), anyList())).thenReturn("The Sun contributes too.");
        JsonNode second = chat(session, "and the sun?", id);
        assertEquals(id, second.get("conversationId").asText());
        verify(ai).generate(eq("and the sun?"), anyList(), argThat(h -> h.size() == 2 && "user".equals(h.get(0).role()) && "assistant".equals(h.get(1).role())));

        mvc.perform(get("/api/v1/conversations/{id}", id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(4)))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].answerMode").value("ai"))
                .andExpect(jsonPath("$.messages[1].sources[0].title").value("Tides - Example"));
    }

    @Test void chatWithoutAProviderReturnsALabelledExtractiveAnswerNotAFakeAiAnswer() throws Exception {
        when(ai.isConfigured()).thenReturn(false);
        JsonNode body = chat(signedIn(), "What causes tides?", null);
        assertEquals("extractive", body.get("answerMode").asText());
        assertTrue(body.get("assistantMessage").get("content").asText().startsWith("Extractive web summary (AI provider not configured)"));
        assertTrue(body.get("notice").asText().contains("No AI model is configured"));
        verify(ai, never()).generate(anyString(), anyList(), anyList());
    }

    @Test void chatStillAnswersWhenWebSearchIsDown() throws Exception {
        when(research.research(anyString())).thenThrow(new ResearchException("Web search is temporarily unavailable."));
        when(ai.generate(anyString(), eq(List.of()), anyList())).thenReturn("From model knowledge.");
        JsonNode body = chat(signedIn(), "What is the capital of France?", null);
        assertEquals("unavailable", body.get("searchStatus").asText());
        assertEquals("From model knowledge.", body.get("assistantMessage").get("content").asText());
        assertEquals(0, body.get("sources").size());
    }

    @Test void providerFailuresSurfaceAsHonestErrorStatuses() throws Exception {
        MockHttpSession session = signedIn();
        for (Object[] c : new Object[][]{{HttpStatus.TOO_MANY_REQUESTS, 429}, {HttpStatus.SERVICE_UNAVAILABLE, 503}, {HttpStatus.GATEWAY_TIMEOUT, 504}, {HttpStatus.BAD_GATEWAY, 502}}) {
            doThrow(new AiProviderException((HttpStatus) c[0], "provider problem")).when(ai).generate(anyString(), anyList(), anyList());
            mvc.perform(post("/api/v1/chat").session(session).contentType(MediaType.APPLICATION_JSON).content(chatBody("a question", null)))
                    .andExpect(status().is((Integer) c[1])).andExpect(jsonPath("$.detail").value("provider problem"));
        }
    }

    @Test void noAnswerAndNoSearchIsAnErrorNotASuccess() throws Exception {
        when(ai.isConfigured()).thenReturn(false);
        when(research.research(anyString())).thenThrow(new ResearchException("Web search is temporarily unavailable. Please try again shortly."));
        mvc.perform(post("/api/v1/chat").session(signedIn()).contentType(MediaType.APPLICATION_JSON).content(chatBody("question", null)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.detail").value(containsString("unavailable")));
    }

    @Test void chatRejectsInvalidInput() throws Exception {
        MockHttpSession s = signedIn();
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"   \"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").exists());
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("invalid JSON")));
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content(chatBody("x".repeat(30001), null))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\",\"conversationId\":\"not-a-uuid\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.TEXT_PLAIN).content("hi")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/chat").session(s)).andExpect(status().is4xxClientError());
    }

    @Test void unknownResourcesAndMethodsReturnProperClientErrors() throws Exception {
        MockHttpSession s = signedIn();
        mvc.perform(get("/api/v1/conversations/{id}", UUID.randomUUID()).session(s)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/conversations/{id}", UUID.randomUUID()).session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations/not-a-uuid").session(s)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/does-not-exist").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/chat").session(s)).andExpect(status().isMethodNotAllowed());
    }

    @Test void signedInUsersAreIsolatedFromEachOther() throws Exception {
        when(ai.generate(anyString(), anyList(), anyList())).thenReturn("answer");
        MockHttpSession a = register("iso-a@example.org"), b = register("iso-b@example.org");
        String id = chat(a, "question from user a", null).get("conversationId").asText();
        mvc.perform(get("/api/v1/conversations").session(a)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/conversations").session(b)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/conversations/{id}", id).session(b)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/conversations/{id}", id).session(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/chat").session(b).contentType(MediaType.APPLICATION_JSON).content(chatBody("hijack", id))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/conversations/{id}", id).session(a)).andExpect(status().isOk());
    }

    @Test void conversationListReturnsSummariesWithoutMessages() throws Exception {
        when(ai.generate(anyString(), anyList(), anyList())).thenReturn("answer");
        MockHttpSession s = signedIn();
        chat(s, "first question", null);
        mvc.perform(get("/api/v1/conversations").session(s)).andExpect(jsonPath("$[0].title").value("first question"))
                .andExpect(jsonPath("$[0].messages").doesNotExist());
    }

    @Test void conversationCrudWorksForSignedInUsers() throws Exception {
        MockHttpSession s = signedIn();
        MvcResult created = mvc.perform(post("/api/v1/conversations").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Research notes\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Research notes")).andReturn();
        String id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").asText();
        mvc.perform(post("/api/v1/conversations").session(s)).andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("New conversation"));
        mvc.perform(get("/api/v1/conversations").session(s)).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(delete("/api/v1/conversations/{id}", id).session(s)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/conversations").session(s)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/conversations/{id}", id).session(s)).andExpect(status().isNotFound());
    }

    @Test void statusReportsConfigurationWithoutSecrets() throws Exception {
        mvc.perform(get("/api/v1/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiConfigured").value(true)).andExpect(jsonPath("$.model").value("stub-model"))
                .andExpect(jsonPath("$.provider").value("stub.local")).andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(content().string(not(containsStringIgnoringCase("key"))));
        when(ai.isConfigured()).thenReturn(false);
        mvc.perform(get("/api/v1/status")).andExpect(jsonPath("$.aiConfigured").value(false));
        MockHttpSession s = register("status@example.org");
        mvc.perform(get("/api/v1/status").session(s)).andExpect(jsonPath("$.account.email").value("status@example.org"));
    }

    @Test void accountLifecycleAndCredentialErrors() throws Exception {
        String email = "life@example.org", password = "correct horse battery staple";
        MockHttpSession s = register(email);
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Dup", "email", email.toUpperCase(), "password", password)))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Short", "email", "short@example.org", "password", "short")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Bad", "email", "not-an-email", "password", password)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/logout").session(s)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        String wrongPassword = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("email", email, "password", "wrong password here"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("email", "nobody@example.org", "password", "wrong password here"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(wrongPassword).get("detail"), json.readTree(unknownEmail).get("detail"), "must not reveal which emails exist");
    }

    @Test void summarizeProducesAnExtractiveSummaryAndValidatesInput() throws Exception {
        MockHttpSession s = signedIn();
        String text = "Solar panels convert sunlight into electricity using photovoltaic cells. The cells are made of silicon. "
                + "Silicon cells are connected in series to form panels. Panels are mounted on roofs or in large solar farms. "
                + "Electricity from panels can be stored in batteries or fed into the grid. The cost of panels has fallen sharply.";
        mvc.perform(post("/api/v1/summarize").session(s).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("text", text, "instruction", "2 bullet points"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary", containsString("- ")));
        mvc.perform(post("/api/v1/summarize").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/summarize").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"ok\",\"instruction\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
    }

    @Test void securityHeadersAndNoStoreOnApi() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", allOf(containsString("default-src 'self'"), containsString("script-src 'self';"), containsString("frame-ancestors 'none'"))));
    }

    @Test void crossOriginBrowserMutationsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "https://attacker.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/chat").header("Sec-Fetch-Site", "cross-site").contentType(MediaType.APPLICATION_JSON).content(chatBody("hi", null))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "http://localhost")).andExpect(status().isNoContent());
    }

    @Test void uiAndItsAssetsAreServed() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("Seekora")));
        mvc.perform(get("/css/seekora.css")).andExpect(status().isOk());
        mvc.perform(get("/js/seekora.js")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
