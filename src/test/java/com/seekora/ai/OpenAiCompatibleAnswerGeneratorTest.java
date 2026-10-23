package com.seekora.ai;

import com.seekora.ai.AnswerGenerator.Turn;
import com.seekora.web.WebResearchService.ResearchSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleAnswerGeneratorTest {
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private String start(Consumer<HttpExchange> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            handler.accept(exchange);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void reply(HttpExchange ex, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    private static String ok(String content) { return "{\"choices\":[{\"message\":{\"content\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(content) + "}}]}"; }
    private static final ResearchSource SRC = new ResearchSource("Example", "https://example.org", "evidence text");

    @Test void postsEvidenceAndReturnsProviderAnswer() throws Exception {
        String url = start(ex -> reply(ex, 200, ok("Answer [1]")));
        var generator = new OpenAiCompatibleAnswerGenerator("test-key", "test-model", url);
        assertEquals("Answer [1]", generator.generate("sample question", List.of(SRC), List.of()));
        assertTrue(lastBody.get().contains("sample question"));
        assertTrue(lastBody.get().contains("https://example.org"));
        assertTrue(lastBody.get().contains("Retrieved sources"));
        assertEquals("Bearer test-key", lastAuth.get());
        assertTrue(lastBody.get().contains("\"model\":\"test-model\""));
    }

    @Test void answersWithoutSourcesUsingTheGeneralKnowledgePrompt() throws Exception {
        String url = start(ex -> reply(ex, 200, ok("Use a for loop.")));
        var generator = new OpenAiCompatibleAnswerGenerator("k", "m", url);
        assertEquals("Use a for loop.", generator.generate("How do I loop in Java?", List.of(), List.of()));
        assertFalse(lastBody.get().contains("Retrieved sources"));
        assertTrue(lastBody.get().contains("No web sources were retrieved"));
    }

    @Test void sendsRecentConversationTurnsForFollowUps() throws Exception {
        String url = start(ex -> reply(ex, 200, ok("ok")));
        var generator = new OpenAiCompatibleAnswerGenerator("k", "m", url);
        generator.generate("and in Python?", List.of(), List.of(new Turn("user", "How do I loop in Java?"), new Turn("assistant", "Use for."), new Turn("system", "IGNORED-ROLE")));
        String body = lastBody.get();
        assertTrue(body.contains("How do I loop in Java?") && body.contains("Use for."));
        assertFalse(body.contains("IGNORED-ROLE"), "only user/assistant turns may be forwarded");
    }

    @Test void stripsReasoningBlocksFromLocalReasoningModels() throws Exception {
        String url = start(ex -> reply(ex, 200, ok("<think>private chain of thought</think>Final answer.")));
        assertEquals("Final answer.", new OpenAiCompatibleAnswerGenerator("k", "m", url).generate("q", List.of(), List.of()));
    }

    @Test void localServerNeedsNoApiKeyAndSendsNoAuthorizationHeader() throws Exception {
        String url = start(ex -> reply(ex, 200, ok("local answer")));
        var generator = new OpenAiCompatibleAnswerGenerator("", "llama3.2", url);
        assertTrue(generator.isConfigured(), "a loopback endpoint (Ollama/LM Studio) is usable without a key");
        assertEquals("local answer", generator.generate("q", List.of(), List.of()));
        assertNull(lastAuth.get());
    }

    @Test void remoteProviderWithoutKeyIsNotConfiguredAndReportsHowToFixIt() {
        var generator = new OpenAiCompatibleAnswerGenerator("", "model", "https://example.org/v1");
        assertFalse(generator.isConfigured());
        AiProviderException error = assertThrows(AiProviderException.class, () -> generator.generate("question", List.of(), List.of()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertTrue(error.getMessage().contains("SEEKORA_AI_API_KEY"));
    }

    @Test void mapsProviderRateLimitsWithoutLeakingProviderBody() throws Exception {
        String url = start(ex -> reply(ex, 429, "sensitive provider response with test-key"));
        var generator = new OpenAiCompatibleAnswerGenerator("test-key", "m", url);
        AiProviderException error = assertThrows(AiProviderException.class, () -> generator.generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertFalse(error.getMessage().contains("sensitive"));
        assertFalse(error.getMessage().contains("test-key"));
    }

    @Test void mapsAuthenticationFailuresToAConfigurationError() throws Exception {
        String url = start(ex -> reply(ex, 401, "{\"error\":\"bad key\"}"));
        AiProviderException error = assertThrows(AiProviderException.class,
                () -> new OpenAiCompatibleAnswerGenerator("wrong", "m", url).generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertTrue(error.getMessage().contains("credentials"));
    }

    @Test void mapsUnknownModelToAHelpfulConfigurationHint() throws Exception {
        String url = start(ex -> reply(ex, 404, "{\"error\":\"model not found\"}"));
        AiProviderException error = assertThrows(AiProviderException.class,
                () -> new OpenAiCompatibleAnswerGenerator("k", "no-such-model", url).generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
        assertTrue(error.getMessage().contains("SEEKORA_AI_MODEL"));
    }

    @Test void mapsServerErrorsToBadGateway() throws Exception {
        String url = start(ex -> reply(ex, 500, "boom"));
        AiProviderException error = assertThrows(AiProviderException.class,
                () -> new OpenAiCompatibleAnswerGenerator("k", "m", url).generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
    }

    @Test void timesOutSlowProvidersInsteadOfHanging() throws Exception {
        String url = start(ex -> { try { Thread.sleep(2500); } catch (InterruptedException ignored) { } reply(ex, 200, ok("late")); });
        var generator = new OpenAiCompatibleAnswerGenerator("k", "m", url, 1, "0.2");
        long begin = System.nanoTime();
        AiProviderException error = assertThrows(AiProviderException.class, () -> generator.generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
        assertTrue((System.nanoTime() - begin) / 1_000_000 < 2400, "must fail at the configured timeout, not wait for the slow reply");
    }

    @Test void rejectsEmptyAndMalformedProviderResponses() throws Exception {
        String url = start(ex -> reply(ex, 200, "{\"choices\":[]}"));
        var generator = new OpenAiCompatibleAnswerGenerator("k", "m", url);
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(AiProviderException.class, () -> generator.generate("q", List.of(), List.of())).getStatus());
        server.stop(0);
        String url2 = start(ex -> reply(ex, 200, "this is not json"));
        var generator2 = new OpenAiCompatibleAnswerGenerator("k", "m", url2);
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(AiProviderException.class, () -> generator2.generate("q", List.of(), List.of())).getStatus());
    }

    @Test void unreachableProviderIsReportedAsGatewayTimeout() {
        var generator = new OpenAiCompatibleAnswerGenerator("k", "m", "http://127.0.0.1:1/v1", 2, "0.2");
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, assertThrows(AiProviderException.class, () -> generator.generate("q", List.of(), List.of())).getStatus());
    }

    @Test void blankTemperatureIsOmittedFromTheRequest() throws Exception {
        String url = start(ex -> reply(ex, 200, ok("ok")));
        new OpenAiCompatibleAnswerGenerator("k", "m", url, 30, "").generate("q", List.of(), List.of());
        assertFalse(lastBody.get().contains("temperature"));
        server.stop(0);
        String url2 = start(ex -> reply(ex, 200, ok("ok")));
        new OpenAiCompatibleAnswerGenerator("k", "m", url2, 30, "0.7").generate("q", List.of(), List.of());
        assertTrue(lastBody.get().contains("\"temperature\":0.7"));
    }

    // ---- transient-failure retry policy

    private OpenAiCompatibleAnswerGenerator configured(String url) { return new OpenAiCompatibleAnswerGenerator("test-key", "m", url, 5, "0.2", 2, 1); }

    @Test void aTransient503IsRetriedOnceAndThenSucceeds() throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String url = start(ex -> { if (calls.incrementAndGet() == 1) reply(ex, 503, "{}"); else reply(ex, 200, ok("Recovered answer")); });
        assertEquals("Recovered answer", configured(url).generate("q", List.of(), List.of()));
        assertEquals(2, calls.get());
    }

    @Test void retriesAreBoundedAndTheFinalErrorIsReported() throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String url = start(ex -> { calls.incrementAndGet(); reply(ex, 502, "{}"); });
        AiProviderException e = assertThrows(AiProviderException.class, () -> configured(url).generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        assertEquals(2, calls.get(), "one attempt plus exactly one retry");
    }

    @Test void credentialQuotaAndBadRequestErrorsAreNeverRetried() throws IOException {
        for (int code : new int[]{401, 403, 429, 404, 400}) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            String url = start(ex -> { calls.incrementAndGet(); reply(ex, code, "{\"error\":\"sk-secret-value rejected\"}"); });
            AiProviderException e = assertThrows(AiProviderException.class, () -> configured(url).generate("q", List.of(), List.of()));
            assertEquals(1, calls.get(), "HTTP " + code + " must not be retried");
            assertFalse(e.getMessage().contains("sk-secret"), "provider bodies must not reach the browser");
            server.stop(0);
        }
    }

    @Test void aSlowProviderTimesOutWithoutBeingRetried() throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String url = start(ex -> { calls.incrementAndGet(); try { Thread.sleep(2500); } catch (InterruptedException ignored) { } });
        OpenAiCompatibleAnswerGenerator slow = new OpenAiCompatibleAnswerGenerator("test-key", "m", url, 1, "0.2", 2, 1);
        AiProviderException e = assertThrows(AiProviderException.class, () -> slow.generate("q", List.of(), List.of()));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.getStatus());
        assertEquals(1, calls.get());
    }

    @Test void theApiKeyIsSentAsABearerTokenAndNeverInTheBody() throws IOException {
        String url = start(ex -> reply(ex, 200, ok("fine")));
        configured(url).generate("q", List.of(), List.of());
        assertEquals("Bearer test-key", lastAuth.get());
        assertFalse(lastBody.get().contains("test-key"));
    }
}
