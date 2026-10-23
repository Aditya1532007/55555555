package com.seekora.account;

import com.seekora.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import static com.seekora.account.AccountDtos.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    public static final String SESSION_ACCOUNT_ID = "seekora.accountId";
    private final AccountService accounts;

    /** Failed sign-ins per email address per 15 minutes; slows password guessing without revealing which emails exist. */
    private final RateLimiter failedLogins;

    public AccountController(AccountService accounts,
                             @Value("${seekora.limits.failed-logins-per-15-min:10}") int maxFailedLogins) {
        this.accounts = accounts;
        this.failedLogins = new RateLimiter(maxFailedLogins, Duration.ofMinutes(15));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        AccountUser user = accounts.register(request);
        establishSession(servletRequest, user.getId());
        return toResponse(user);
    }

    @PostMapping("/login")
    public AccountResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String key = request.email().trim().toLowerCase(Locale.ROOT);
        if (failedLogins.isBlocked(key)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-in attempts. Please try again in a few minutes.");
        }
        AccountUser user;
        try {
            user = accounts.authenticate(request);
        } catch (InvalidCredentialsException ex) {
            failedLogins.tryHit(key);
            throw ex;
        }
        failedLogins.reset(key);
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
