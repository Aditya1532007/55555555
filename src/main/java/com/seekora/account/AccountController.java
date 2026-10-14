package com.seekora.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static com.seekora.account.AccountDtos.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    public static final String SESSION_ACCOUNT_ID = "seekora.accountId";
    private final AccountService accounts;

    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        AccountUser user = accounts.register(request);
        establishSession(servletRequest, user.getId());
        return toResponse(user);
    }

    @PostMapping("/login")
    public AccountResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        AccountUser user = accounts.authenticate(request);
        establishSession(servletRequest, user.getId());
        return toResponse(user);
    }

    @GetMapping("/me")
    public AccountResponse me(HttpServletRequest request) {
        UUID id = currentAccountId(request);
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        return toResponse(accounts.getById(id));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    public static UUID currentAccountId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute(SESSION_ACCOUNT_ID);
        return value instanceof UUID id ? id : null;
    }

    private void establishSession(HttpServletRequest request, UUID accountId) {
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) oldSession.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_ACCOUNT_ID, accountId);
    }

    private AccountResponse toResponse(AccountUser user) {
        return new AccountResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
