package com.seekora.api;

import com.seekora.account.AccountController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies who is calling: a signed-in account, or an anonymous browser session. Anonymous callers may only
 * see conversations their own session created; without this every guest could list every other guest's chats.
 */
public record Caller(UUID userId, Set<UUID> guestConversationIds) {
    static final String GUEST_CONVERSATIONS = "seekora.guestConversations";

    public static Caller user(UUID userId) { return new Caller(userId, Set.of()); }
    public static Caller guest(Set<UUID> conversationIds) { return new Caller(null, conversationIds); }
    public boolean isGuest() { return userId == null; }

    /** @param createSession true for requests that may create data (so a guest session is established). */
    @SuppressWarnings("unchecked")
    public static Caller from(HttpServletRequest request, boolean createSession) {
        UUID accountId = AccountController.currentAccountId(request);
        if (accountId != null) return user(accountId);
        HttpSession session = request.getSession(createSession);
        if (session == null) return guest(Set.of());
        Object existing = session.getAttribute(GUEST_CONVERSATIONS);
        if (existing instanceof Set<?> set) return guest((Set<UUID>) set);
        Set<UUID> created = ConcurrentHashMap.newKeySet();
        session.setAttribute(GUEST_CONVERSATIONS, created);
        return guest(created);
    }
}
