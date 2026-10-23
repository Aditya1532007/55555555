package com.seekora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekora.account.AccountUser;
import com.seekora.account.AccountUserRepository;
import com.seekora.account.PasswordHasher;
import com.seekora.ai.AnswerGenerator;
import com.seekora.web.WebResearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies that sign-in is enforced by the backend itself, plus the account rules around it. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seekora-login-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class MandatoryLoginTest {
    private static final String PASSWORD = "correct horse battery staple";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountUserRepository users;
    @Autowired PasswordHasher hasher;
    @MockitoBean AnswerGenerator ai;
    @MockitoBean WebResearchService research;

    @BeforeEach void stubs() {
        when(ai.isConfigured()).thenReturn(true);
        when(ai.modelName()).thenReturn("stub-model");
        when(ai.generate(anyString(), anyList(), anyList())).thenReturn("A real answer.");
        when(research.research(anyString())).thenReturn(new WebResearchService.ResearchResult("summary",
                List.of(new WebResearchService.ResearchSource("Src", "https://example.org/a", "Some source text that is long enough."))));
    }

    private MvcResult signup(String name, String email, String password, String confirm) throws Exception {
        Map<String, String> body = confirm == null ? Map.of("name", name, "email", email, "password", password)
                : Map.of("name", name, "email", email, "password", password, "confirmPassword", confirm);
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andReturn();
    }

    private MockHttpSession registerOk(String email) throws Exception {
        MvcResult r = signup("Tester", email, PASSWORD, PASSWORD);
        assertEquals(201, r.getResponse().getStatus());
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String chatJson() throws Exception { return json.writeValueAsString(Map.of("message", "What is artificial intelligence?")); }

    @Test void everyProtectedEndpointRejectsAnonymousCallersWithoutTouchingTheProvider() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(chatJson()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(containsString("sign in")));
        mvc.perform(post("/api/v1/summarize").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Some text. More text.\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/conversations/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/conversations/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/some-future-endpoint")).andExpect(status().isUnauthorized()); // deny by default
        mvc.perform(post("/api/v1/chat/").contentType(MediaType.APPLICATION_JSON).content(chatJson())).andExpect(status().isUnauthorized());
        verifyNoInteractions(research);
        verify(ai, never()).generate(anyString(), anyList(), anyList());
    }

    @Test void publicEndpointsStayReachableWhileSignedOut() throws Exception {
        mvc.perform(get("/api/v1/status")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test void signedInUsersCanChatAndLogoutBlocksSearchAgain() throws Exception {
        MockHttpSession s = registerOk("flow-" + UUID.randomUUID() + "@example.org");
        MvcResult r = mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content(chatJson()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsByteArray());
        // The exact fields the web UI reads (static/js/seekora.js).
        assertEquals("A real answer.", body.get("assistantMessage").get("content").asText());
        assertEquals("ai", body.get("answerMode").asText());
        assertTrue(body.get("notice").asText().length() > 10);
        assertEquals("https://example.org/a", body.get("sources").get(0).get("url").asText());
        assertEquals("Src", body.get("sources").get(0).get("title").asText());
        assertFalse(body.get("conversationId").asText().isBlank());

        mvc.perform(post("/api/v1/auth/logout").session(s)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content(chatJson())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/conversations").session(s)).andExpect(status().isUnauthorized());
    }

    @Test void expiredOrInvalidatedSessionsAreRejected() throws Exception {
        MockHttpSession s = registerOk("expire-" + UUID.randomUUID() + "@example.org");
        s.invalidate(); // what the container does when the idle timeout passes
        mvc.perform(post("/api/v1/chat").session(s).contentType(MediaType.APPLICATION_JSON).content(chatJson())).andExpect(status().isUnauthorized());
    }

    @Test void loginCreatesAFreshSessionAndAnErrorBodyUsesTheDetailField() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.org";
        MockHttpSession first = registerOk(email);
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email, "password", PASSWORD)))).andExpect(status().isOk()).andReturn();
        MockHttpSession second = (MockHttpSession) login.getRequest().getSession(false);
        assertNotEquals(first.getId(), second.getId());
        mvc.perform(get("/api/v1/auth/me").session(second)).andExpect(jsonPath("$.name").value("Tester"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email, "password", "not the password")))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email or password is incorrect."));
    }

    @Test void signupRequiresMatchingPasswordsAndRejectsDuplicates() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.org";
        MvcResult mismatch = signup("Mismatch", email, PASSWORD, PASSWORD + "x");
        assertEquals(400, mismatch.getResponse().getStatus());
        assertTrue(mismatch.getResponse().getContentAsString().contains("Passwords do not match"));
        assertTrue(users.findByEmail(email).isEmpty(), "a rejected signup must not create an account");
        assertEquals(201, signup("Dup", email, PASSWORD, PASSWORD).getResponse().getStatus());
        assertEquals(409, signup("Dup Again", email.toUpperCase(), PASSWORD, PASSWORD).getResponse().getStatus());
        assertEquals(400, signup("Weak", "weak-" + UUID.randomUUID() + "@example.org", "short", "short").getResponse().getStatus());
    }

    @Test void passwordsAreStoredAsSaltedHashesNeverPlaintext() throws Exception {
        String email = "hash-" + UUID.randomUUID() + "@example.org";
        registerOk(email);
        AccountUser stored = users.findByEmail(email).orElseThrow();
        assertNotEquals(PASSWORD, stored.getPasswordHash());
        assertFalse(stored.getPasswordHash().contains(PASSWORD));
        assertTrue(hasher.matches(PASSWORD, stored.getPasswordHash()));
        assertFalse(hasher.matches(PASSWORD + "!", stored.getPasswordHash()));
        assertNotEquals(hasher.hash(PASSWORD), hasher.hash(PASSWORD), "each hash must use its own random salt");
        assertTrue(stored.getPasswordHash().startsWith("310000$"));
    }

    @Test void repeatedFailedLoginsAreThrottledPerEmailWithoutRevealingWhetherItExists() throws Exception {
        String email = "brute-" + UUID.randomUUID() + "@example.org"; // never registered
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("email", email, "password", "guess number " + i + " here")))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email, "password", "one more guess here")))).andExpect(status().isTooManyRequests());
    }

    /** Render terminates TLS and forwards plain HTTP with X-Forwarded-* headers; the origin check must accept the public https origin. */
    @Test void sameOriginRequestsBehindRendersHttpsProxyAreAccepted() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "https://seekora.onrender.com")
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "seekora.onrender.com").header("X-Forwarded-Port", "443"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "https://seekora.onrender.com")
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "seekora.onrender.com"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").header("Origin", "https://evil.example")
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "seekora.onrender.com"))
                .andExpect(status().isForbidden());
    }

    @Test void theUiNeverDependsOnLocalhostOrAnAbsoluteBackendUrl() throws Exception {
        for (String file : List.of("static/js/seekora.js", "static/index.html")) {
            String text = new String(getClass().getClassLoader().getResourceAsStream(file).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(text.contains("localhost"), file + " must not reference localhost");
            assertFalse(text.contains("127.0.0.1"), file + " must not reference a loopback address");
            assertFalse(text.matches("(?s).*fetch\\(\\s*['\"`]https?://.*"), file + " must use relative API URLs");
        }
        String js = new String(getClass().getClassLoader().getResourceAsStream("static/js/seekora.js").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(js.contains("function apiBase(){return '/api/v1'}"));
        assertTrue(js.contains("credentials:'same-origin'"));
    }
}
