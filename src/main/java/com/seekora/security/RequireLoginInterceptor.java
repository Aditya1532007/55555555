package com.seekora.security;

import com.seekora.account.AccountController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Server-side enforcement of mandatory sign-in. Everything under /api/ requires a signed-in session except the
 * small allow-list below, so a newly added endpoint is protected by default. Hiding the UI is not enough: this
 * runs on the backend for every API call.
 */
@Component
public class RequireLoginInterceptor implements HandlerInterceptor {
    /** Endpoints that must work while signed out. {@code /auth/me} answers 401 itself. */
    static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/status", "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/me");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS preflight requests never carry cookies; the CORS layer decides them.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (PUBLIC_PATHS.contains(path.replaceAll("/+$", ""))) return true;
        if (AccountController.currentAccountId(request) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        }
        return true;
    }
}
