package com.seekora.api;

import com.seekora.account.AccountController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** The signed-in account making a request. Anonymous access no longer exists: every caller has an account. */
public record Caller(UUID userId) {
    public static Caller user(UUID userId) { return new Caller(userId); }

    /** Resolves the caller from the session, or answers 401 (defence in depth behind the login interceptor). */
    public static Caller from(HttpServletRequest request) {
        UUID accountId = AccountController.currentAccountId(request);
        if (accountId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        return user(accountId);
    }
}
