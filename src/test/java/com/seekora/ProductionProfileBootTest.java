package com.seekora;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the real application on an embedded Tomcat with the {@code prod} profile (an in-memory H2 URL stands in for
 * PostgreSQL, which needs a server) and checks what only a real container can show: the session cookie attributes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SEEKORA_DB_URL=jdbc:h2:mem:seekora-prod-boot;DB_CLOSE_DELAY=-1",
        "SEEKORA_DB_USER=sa", "SEEKORA_DB_PASSWORD=unused-in-memory",
        "SEEKORA_AI_API_KEY="
})
@ActiveProfiles("prod")
class ProductionProfileBootTest {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void productionProfileStartsAndIssuesSecureHttpOnlySameSiteCookies() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"Cookie Tester\",\"email\":\"cookie@example.org\",\"password\":\"correct horse battery staple\",\"confirmPassword\":\"correct horse battery staple\"}")));
        assertEquals(201, r.statusCode(), r.body());
        String cookie = String.join(";", r.headers().allValues("Set-Cookie"));
        assertTrue(cookie.contains("JSESSIONID="), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("Secure"), cookie);
        assertTrue(cookie.toLowerCase().contains("samesite=lax"), cookie);
        assertFalse(cookie.contains("Max-Age=0"));
    }

    @Test void withoutAnAiKeyProductionReportsAClearConfigurationErrorInsteadOfScraping() throws Exception {
        HttpResponse<String> reg = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"No Key\",\"email\":\"nokey@example.org\",\"password\":\"correct horse battery staple\"}")));
        assertEquals(201, reg.statusCode());
        String session = reg.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        HttpResponse<String> status = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/status")).header("Cookie", session).GET());
        assertTrue(status.body().contains("\"aiConfigured\":false"), status.body());
        assertTrue(status.body().contains("\"aiRequired\":true"), status.body());
        HttpResponse<String> chat = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat")).header("Cookie", session)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"What is artificial intelligence?\"}")));
        assertEquals(503, chat.statusCode(), chat.body());
        assertTrue(chat.body().contains("SEEKORA_AI_API_KEY"), chat.body());
        // Anonymous callers never reach that point.
        HttpResponse<String> anon = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")));
        assertEquals(401, anon.statusCode());
    }
}
