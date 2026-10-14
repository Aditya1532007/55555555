package com.seekora.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds baseline browser protections (including a Content-Security-Policy) and blocks cross-origin browser
 * mutations of session-backed APIs, except from origins explicitly listed in {@code seekora.cors.allowed-origins}.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private final Set<String> allowedOrigins;
    private final String contentSecurityPolicy;

    public SecurityHeadersFilter(@Value("${seekora.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.replaceAll("/+$", "").toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        String connect = "'self'" + this.allowedOrigins.stream().map(o -> " " + o).collect(Collectors.joining());
        this.contentSecurityPolicy = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; font-src 'self' data:; connect-src " + connect + "; object-src 'none'; "
                + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), geolocation=(), microphone=(self)");
        response.setHeader("Content-Security-Policy", contentSecurityPolicy);
        if (request.isSecure()) response.setHeader("Strict-Transport-Security", "max-age=31536000");

        boolean api = request.getRequestURI().startsWith(request.getContextPath() + "/api/");
        if (api) response.setHeader("Cache-Control", "no-store"); // conversations are private data

        if (api && isMutation(request.getMethod()) && !isAllowedOrigin(request.getHeader("Origin"))) {
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if ("cross-site".equalsIgnoreCase(fetchSite) || !sameOriginIfProvided(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-origin API requests are not allowed.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin.trim().replaceAll("/+$", "").toLowerCase(Locale.ROOT));
    }

    private boolean sameOriginIfProvided(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return true;
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return false;
            int originPort = effectivePort(uri.getScheme(), uri.getPort());
            int requestPort = effectivePort(request.getScheme(), request.getServerPort());
            return uri.getHost().toLowerCase(Locale.ROOT).equals(request.getServerName().toLowerCase(Locale.ROOT))
                    && uri.getScheme().equalsIgnoreCase(request.getScheme()) && originPort == requestPort;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private int effectivePort(String scheme, int port) {
        return port != -1 ? port : "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private boolean isMutation(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }
}
