package com.seekora.api;

import com.seekora.security.RateLimiter;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.seekora.api.ApiDtos.*;

@RestController
@RequestMapping("/api/v1")
public class AssistantController {
    private final AssistantService service;
    private final RateLimiter perAccountChat;
    private final RateLimiter globalChat;

    public AssistantController(AssistantService service,
                               @Value("${seekora.limits.chat-per-account-per-hour:60}") int perAccount,
                               @Value("${seekora.limits.chat-global-per-hour:1000}") int global) {
        this.service = service;
        this.perAccountChat = new RateLimiter(perAccount, Duration.ofHours(1));
        this.globalChat = new RateLimiter(global, Duration.ofHours(1));
    }

    @GetMapping("/status")
    public StatusResponse status(HttpServletRequest request) {
        // Public on purpose: the UI calls this while signed out to learn whether it must show the login form.
        return service.status(com.seekora.account.AccountController.currentAccountId(request));
    }
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        Caller caller = Caller.from(servletRequest);
        // Every chat call costs provider tokens and search quota, so cap usage per account and overall.
        if (!perAccountChat.tryHit(caller.userId().toString()) || !globalChat.tryHit("*")) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You have reached the hourly question limit. Please try again later.");
        }
        return service.chat(request, caller);
    }
    @PostMapping("/summarize")
    public SummaryResponse summarize(@Valid @RequestBody SummaryRequest request) { return new SummaryResponse(service.summarize(request)); }
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto create(@Valid @RequestBody(required = false) CreateConversationRequest request, HttpServletRequest servletRequest) {
        return service.create(request == null ? new CreateConversationRequest(null) : request, Caller.from(servletRequest));
    }
    @GetMapping("/conversations")
    public List<ConversationDto> list(HttpServletRequest servletRequest) {
        return service.list(Caller.from(servletRequest));
    }
    @GetMapping("/conversations/{id}")
    public ConversationDto get(@PathVariable UUID id, HttpServletRequest servletRequest) {
        return service.get(id, Caller.from(servletRequest));
    }
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest servletRequest) {
        service.delete(id, Caller.from(servletRequest));
    }
}
