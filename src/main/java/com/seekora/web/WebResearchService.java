package com.seekora.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Retrieves public web evidence for a question: searches DuckDuckGo's HTML endpoint (falling back to the
 * Wikipedia search API), then reads a few result pages in parallel. All fetches are restricted to public
 * http(s) hosts, and every redirect hop is re-validated.
 */
@Service
public class WebResearchService {
    private static final Logger log = LoggerFactory.getLogger(WebResearchService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; Seekora/1.0; +https://localhost)";
    private static final int RESULT_LIMIT = 4;
    private static final int MAX_PAGE_BYTES = 1_000_000;
    private static final int MAX_PAGE_CHARS = 14_000;
    private static final int MAX_QUERY_CHARS = 200;
    private static final int MAX_REDIRECTS = 3;
    private static final int PAGE_TIMEOUT_MS = 7_000;
    private static final int SEARCH_TIMEOUT_MS = 8_000;
    private static final long PAGES_BUDGET_SECONDS = 14;
    private static final long CACHE_TTL_MILLIS = 10 * 60_000L;
    private static final int CACHE_MAX_ENTRIES = 64;
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "that", "this", "from", "what",
            "when", "where", "which", "who", "why", "how", "does", "about", "into", "your", "are", "was", "were",
            "have", "has", "will", "can", "could", "would", "should", "their", "there", "they", "them", "then",
            "than", "you", "all", "any", "not", "but", "use", "using", "get", "give", "tell", "explain", "find",
            "please", "is", "of", "in", "on", "to", "a", "an", "or", "as", "by", "be", "at", "do", "did", "me", "it");

    private final String searchUrl;
    private final String wikipediaApiUrl;
    private final boolean allowPrivateHosts;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, CachedResult> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    @Autowired
    public WebResearchService(
            @Value("${seekora.research.search-url:https://html.duckduckgo.com/html/}") String searchUrl,
            @Value("${seekora.research.wikipedia-api-url:https://en.wikipedia.org/w/api.php}") String wikipediaApiUrl) {
        this(searchUrl, wikipediaApiUrl, false);
    }

    /** Test seam: {@code allowPrivateHosts} lets tests point the service at a loopback stub server. Never enabled in production. */
    WebResearchService(String searchUrl, String wikipediaApiUrl, boolean allowPrivateHosts) {
        this.searchUrl = searchUrl;
        this.wikipediaApiUrl = wikipediaApiUrl;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public ResearchResult research(String rawQuery) {
        String query = normalizeQuery(rawQuery);
        if (query.isBlank()) throw new ResearchException("There was nothing to search for.");
        String cacheKey = query.toLowerCase(Locale.ROOT);
        ResearchResult cached = cacheGet(cacheKey);
        if (cached != null) return cached;

        List<SearchHit> hits = searchWithFallback(query);
        List<ResearchSource> sources = readPages(hits);
        if (sources.isEmpty()) {
            // Pages may block automated readers; the search engine's own snippets are still real, citable text.
            sources = hits.stream().filter(h -> h.snippet().length() >= 40)
                    .map(h -> new ResearchSource(h.title(), h.url(), h.snippet())).toList();
        }
        if (sources.isEmpty()) {
            throw new ResearchException("The web search did not return readable pages. Please try a different question.");
        }
        ResearchResult result = new ResearchResult(LocalSummarizer.summarizeSources(query, sources), sources);
        cachePut(cacheKey, result);
        return result;
    }

    // ------------------------------------------------------------------ search

    private List<SearchHit> searchWithFallback(String query) {
        boolean anyFailure = false;
        try {
            List<SearchHit> hits = searchDuckDuckGo(query);
            if (!hits.isEmpty()) return hits;
            log.info("DuckDuckGo returned no usable results; trying Wikipedia search");
        } catch (IOException | RuntimeException e) {
            anyFailure = true;
            log.warn("DuckDuckGo search failed ({}); trying Wikipedia search", e.getClass().getSimpleName());
        }
        try {
            List<SearchHit> hits = searchWikipedia(query);
            if (!hits.isEmpty()) return hits;
        } catch (IOException | RuntimeException e) {
            anyFailure = true;
            log.warn("Wikipedia search failed ({})", e.getClass().getSimpleName());
        }
        throw new ResearchException(anyFailure
                ? "Web search is temporarily unavailable. Please try again shortly."
                : "The web search returned no results. Please try rephrasing the question.");
    }

    private List<SearchHit> searchDuckDuckGo(String query) throws IOException {
        Connection.Response response = Jsoup.connect(searchUrl).data("q", query)
                .userAgent(USER_AGENT).header("Accept-Language", "en")
                .timeout(SEARCH_TIMEOUT_MS).maxBodySize(MAX_PAGE_BYTES)
                .followRedirects(true).ignoreHttpErrors(true).execute();
        Document document = response.parse();
        if (response.statusCode() >= 400 || document.selectFirst(".anomaly-modal__modal, form#challenge-form") != null) {
            log.warn("DuckDuckGo refused the automated request (HTTP {}); it may be rate-limiting this IP", response.statusCode());
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (Element link : document.select("a.result__a")) {
            String url = unwrapSearchLink(link.absUrl("href"));
            String title = clean(link.text());
            Element result = link.closest(".result");
            Element snippet = result == null ? null : result.selectFirst(".result__snippet");
            if (title.isBlank() || url.isBlank()) continue;
            try {
                URI safeUri = validatePublicHttpUrl(url);
                hits.add(new SearchHit(title, safeUri.toASCIIString(), snippet == null ? "" : clean(snippet.text())));
            } catch (IllegalArgumentException ignored) {
                // Malformed, unsupported, or private-network destinations are never fetched.
            }
            if (hits.size() == RESULT_LIMIT) break;
        }
        return hits;
    }

    private List<SearchHit> searchWikipedia(String query) throws IOException {
        String keywords = keywordQuery(query);
        Connection.Response response = Jsoup.connect(wikipediaApiUrl)
                .data("action", "query").data("list", "search").data("srsearch", keywords)
                .data("srlimit", String.valueOf(RESULT_LIMIT)).data("format", "json").data("utf8", "1")
                .userAgent("Seekora/1.0 (educational research assistant; https://localhost)")
                .header("Accept", "application/json").ignoreContentType(true)
                .timeout(SEARCH_TIMEOUT_MS).maxBodySize(MAX_PAGE_BYTES).ignoreHttpErrors(true).execute();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Wikipedia search returned HTTP " + response.statusCode());
        }
        URI api = URI.create(wikipediaApiUrl);
        String articleBase = api.getScheme() + "://" + api.getAuthority() + "/wiki/";
        JsonNode results = json.readTree(response.body()).path("query").path("search");
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode node : results) {
            String title = node.path("title").asText("");
            if (title.isBlank()) continue;
            String url = articleBase + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
                    .replace("%3A", ":").replace("%2F", "/").replace("+", "%20");
            String snippet = clean(Jsoup.parse(node.path("snippet").asText("")).text());
            try {
                hits.add(new SearchHit(title, validatePublicHttpUrl(url).toASCIIString(), snippet));
            } catch (IllegalArgumentException ignored) { /* skip unsafe URL */ }
        }
        return hits;
    }

    private String keywordQuery(String query) {
        StringBuilder out = new StringBuilder();
        for (String word : query.split("[\\s?!.,;:]+")) {
            if (!word.isBlank() && !STOP_WORDS.contains(word.toLowerCase(Locale.ROOT))) out.append(word).append(' ');
        }
        String keywords = out.toString().trim();
        return keywords.isBlank() ? query : keywords;
    }

    // ------------------------------------------------------------------ page reading

    private List<ResearchSource> readPages(List<SearchHit> hits) {
        if (hits.isEmpty()) return List.of();
        List<Callable<ResearchSource>> tasks = hits.stream().limit(RESULT_LIMIT)
                .<Callable<ResearchSource>>map(hit -> () -> readPage(hit)).toList();
        List<ResearchSource> sources = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResearchSource>> futures = executor.invokeAll(tasks, PAGES_BUDGET_SECONDS, TimeUnit.SECONDS);
            for (Future<ResearchSource> future : futures) {
                if (future.isCancelled()) continue;
                try {
                    ResearchSource source = future.get();
                    if (source != null) sources.add(source);
                } catch (ExecutionException ignored) { /* page skipped */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResearchException("The web research was interrupted. Please try again.", e);
        }
        return sources;
    }

    private ResearchSource readPage(SearchHit hit) {
        try {
            Connection.Response response = fetchFollowingSafeRedirects(URI.create(hit.url()));
            if (response == null) return null;
            String contentType = response.contentType();
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                return null;
            }
            Document page = response.parse();
            page.select("script,style,noscript,svg,nav,footer,header,aside,form,button,[hidden],[aria-hidden=true],table,figure,.reference,.reflist,.navbox,.infobox,.toc,.metadata,.mw-editsection,.mw-jump-link,.catlinks,.sidebar,.geo,.coordinates,.hatnote,.thumb,.printfooter,.authority-control").remove();
            Element body = page.selectFirst(".mw-parser-output,article,main,[role=main]");
            if (body == null) body = page.body();
            String text = "";
            if (body != null) {
                List<String> paragraphs = body.select("p").eachText().stream()
                        .map(this::clean).filter(paragraph -> paragraph.length() >= 55).toList();
                text = String.join(" ", paragraphs);
                if (text.length() < 180) text = clean(body.text());
            }
            if (text.length() > MAX_PAGE_CHARS) text = text.substring(0, MAX_PAGE_CHARS);
            if (text.length() < 180) text = hit.snippet();
            if (text.isBlank()) return null;
            String title = page.title().isBlank() ? hit.title() : clean(page.title());
            return new ResearchSource(title, hit.url(), text);
        } catch (IOException | IllegalArgumentException e) {
            // Some sites block automated readers, require JavaScript, or redirect somewhere unsafe: skip them.
            return null;
        }
    }

    /** Follows up to {@value #MAX_REDIRECTS} redirects, re-validating every hop against the public-host rules. */
    private Connection.Response fetchFollowingSafeRedirects(URI start) throws IOException {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            current = validatePublicHttpUrl(current.toASCIIString());
            Connection.Response response = Jsoup.connect(current.toASCIIString()).userAgent(USER_AGENT)
                    .timeout(PAGE_TIMEOUT_MS).maxBodySize(MAX_PAGE_BYTES)
                    .followRedirects(false).ignoreHttpErrors(true).execute();
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.header("Location");
                if (location == null || location.isBlank()) return null;
                current = current.resolve(location.trim());
                continue;
            }
            return response;
        }
        return null;
    }

    // ------------------------------------------------------------------ URL safety

    private String unwrapSearchLink(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null && uri.getHost().endsWith("duckduckgo.com")) {
                String query = uri.getRawQuery();
                if (query != null) {
                    for (String pair : query.split("&")) {
                        int equals = pair.indexOf('=');
                        if (equals > 0 && pair.substring(0, equals).equals("uddg")) {
                            return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException ignored) { }
        return url;
    }

    private URI validatePublicHttpUrl(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid source URL", e); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Only public HTTP(S) source URLs are allowed");
        }
        if (allowPrivateHosts) return uri;
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw new IllegalArgumentException("Unsupported source port");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!isPublicAddress(address)) throw new IllegalArgumentException("Private network source URLs are not allowed");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not resolve source host", e);
        }
        return uri;
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)) return false;
            return (first & 0xe0) == 0x20
                    && !(first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8);
        }
        int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
        int c = Byte.toUnsignedInt(bytes[2]);
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 88 && c == 99))) return false;
        if (a == 192 && b == 0 && c == 2) return false;
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
        if (a == 203 && b == 0 && c == 113) return false;
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** Collapses whitespace and caps the query so a huge message can never produce an oversized search request. */
    static String normalizeQuery(String raw) {
        String q = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (q.length() <= MAX_QUERY_CHARS) return q;
        int cut = q.lastIndexOf(' ', MAX_QUERY_CHARS);
        return q.substring(0, cut > 40 ? cut : MAX_QUERY_CHARS).trim();
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\u200B-\\u200F\\uFEFF]", "")
                .replaceAll("\\s+", " ").trim();
    }

    private ResearchResult cacheGet(String key) {
        synchronized (cache) {
            CachedResult entry = cache.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.storedAt() > CACHE_TTL_MILLIS) { cache.remove(key); return null; }
            return entry.result();
        }
    }

    private void cachePut(String key, ResearchResult result) {
        synchronized (cache) { cache.put(key, new CachedResult(result, System.currentTimeMillis())); }
    }

    public record ResearchSource(String title, String url, String text) { }
    public record ResearchResult(String answer, List<ResearchSource> sources) { }
    private record SearchHit(String title, String url, String snippet) { }
    private record CachedResult(ResearchResult result, long storedAt) { }
}
