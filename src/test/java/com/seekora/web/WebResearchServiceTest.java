package com.seekora.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the whole research pipeline against local stub servers that imitate DuckDuckGo, Wikipedia and web pages. */
class WebResearchServiceTest {
    private HttpServer server;
    private String base;
    private final AtomicInteger searchCalls = new AtomicInteger();
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");
    private final Map<String, Integer> searchStatus = new ConcurrentHashMap<>();
    private volatile String ddgBody = "";

    private static final String ARTICLE_1 = "<html><head><title>Photosynthesis explained</title></head><body><nav>menu menu</nav><article>"
            + "<p>Photosynthesis is the process by which green plants and algae convert light energy into chemical energy stored in glucose.</p>"
            + "<p>The process takes place in chloroplasts, where chlorophyll absorbs sunlight and drives the production of sugars from carbon dioxide and water.</p>"
            + "</article><footer>footer junk</footer></body></html>";
    private static final String ARTICLE_2 = "<html><head><title>Plants and light</title></head><body><main>"
            + "<p>Plants use light, water and carbon dioxide to make sugars during photosynthesis, releasing oxygen as a by-product of the reaction.</p>"
            + "</main></body></html>";

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/html/", ex -> {
            searchCalls.incrementAndGet();
            lastQuery.set(queryParam(ex.getRequestURI(), "q"));
            int status = searchStatus.getOrDefault("/html/", 200);
            byte[] body = ddgBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/w/api.php", ex -> {
            int status = searchStatus.getOrDefault("/w/api.php", 200);
            String json = "{\"query\":{\"search\":[{\"title\":\"Photosynthesis\",\"snippet\":\"<span class=\\\"searchmatch\\\">Photosynthesis</span> is a process used by plants\"}]}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        page("/article1", 200, "text/html", ARTICLE_1);
        page("/article2", 200, "text/html", ARTICLE_2);
        page("/wiki/Photosynthesis", 200, "text/html", ARTICLE_1);
        page("/blocked", 403, "text/html", "forbidden");
        page("/plain", 200, "text/plain", "just text, not html");
        server.createContext("/moved", ex -> { ex.getResponseHeaders().add("Location", "/article2"); ex.sendResponseHeaders(302, -1); ex.close(); });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private void page(String path, int status, String type, String body) {
        server.createContext(path, ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    private String ddgResult(String url, String title, String snippet) {
        String wrapped = "//duckduckgo.com/l/?uddg=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&rut=abc";
        return "<div class=\"result\"><a class=\"result__a\" href=\"" + wrapped + "\">" + title + "</a><a class=\"result__snippet\">" + snippet + "</a></div>";
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery() == null ? new String[0] : uri.getRawQuery().split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private WebResearchService service() { return new WebResearchService(base + "/html/", base + "/w/api.php", true); }

    @Test void readsResultPagesExtractsMainTextAndFollowsRedirects() {
        ddgBody = ddgResult(base + "/article1", "Photosynthesis explained", "snippet one that is long enough to be useful")
                + ddgResult(base + "/moved", "Plants and light", "snippet two that is long enough to be useful")
                + ddgResult(base + "/blocked", "Blocked page", "snippet three that is long enough to be useful")
                + ddgResult(base + "/plain", "Plain text", "snippet four that is long enough to be useful");
        var result = service().research("What is photosynthesis?");
        assertEquals(2, result.sources().size(), "403 and non-HTML pages are skipped, the 302 is followed");
        assertEquals("Photosynthesis explained", result.sources().get(0).title());
        assertTrue(result.sources().get(0).text().contains("chloroplasts"));
        assertFalse(result.sources().get(0).text().contains("footer junk"), "boilerplate such as <footer>/<nav> is removed");
        assertEquals("Plants and light", result.sources().get(1).title(), "the 302 redirect target was read");
        assertTrue(result.answer().contains("["), "the extractive summary carries source numbers");
    }

    @Test void fallsBackToWikipediaWhenDuckDuckGoBlocksTheRequest() {
        ddgBody = "<html><body><div class=\"anomaly-modal__modal\">Unfortunately, bots use DuckDuckGo too.</div></body></html>";
        searchStatus.put("/html/", 202);
        var result = service().research("Explain photosynthesis");
        assertEquals(1, result.sources().size());
        assertTrue(result.sources().get(0).url().endsWith("/wiki/Photosynthesis"));
    }

    @Test void reportsTemporaryUnavailabilityWhenEverySearchProviderFails() {
        searchStatus.put("/html/", 500);
        searchStatus.put("/w/api.php", 500);
        ddgBody = "";
        ResearchException error = assertThrows(ResearchException.class, () -> service().research("anything"));
        assertTrue(error.getMessage().toLowerCase().contains("search"));
    }

    @Test void usesSearchSnippetsWhenNoPageCanBeRead() {
        ddgBody = ddgResult(base + "/blocked", "Blocked page", "A search snippet that is long enough to count as evidence text.");
        var result = service().research("blocked topic");
        assertEquals(1, result.sources().size());
        assertTrue(result.sources().get(0).text().startsWith("A search snippet"));
    }

    @Test void capsHugeQueriesBeforeSendingThemToTheSearchEngine() {
        ddgBody = ddgResult(base + "/article1", "t", "s".repeat(50));
        service().research(("alpha ".repeat(4000)).trim());
        assertTrue(lastQuery.get().length() <= 200, "query sent was " + lastQuery.get().length() + " chars");
        assertTrue(lastQuery.get().length() > 100);
    }

    @Test void cachesIdenticalQueriesToAvoidRepeatedWebRequests() {
        ddgBody = ddgResult(base + "/article1", "Photosynthesis explained", "snippet one that is long enough to be useful");
        var service = service();
        service.research("cache me");
        service.research("  Cache   ME ");
        assertEquals(1, searchCalls.get());
    }

    @Test void neverFetchesPrivateNetworkPagesWhenProtectionIsOn() {
        ddgBody = ddgResult(base + "/article1", "Internal admin page", "snippet that is long enough to be useful here");
        searchStatus.put("/w/api.php", 500);
        var strict = new WebResearchService(base + "/html/", base + "/w/api.php", false);
        assertThrows(ResearchException.class, () -> strict.research("internal"));
    }

    @Test void blankQueryIsRejected() {
        assertThrows(ResearchException.class, () -> service().research("   "));
    }
}
