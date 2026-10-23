package com.seekora.api;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

import static com.seekora.api.ApiDtos.*;

@RestController
@RequestMapping("/api/v1")
public class AssistantController {
    private final AssistantService service;
    public AssistantController(AssistantService service) { this.service = service; }

    @GetMapping("/status")
    public StatusResponse status(HttpServletRequest request) {
        return service.status(Caller.from(request, false).userId());
    }
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        UUID accountId = requireAccount(servletRequest);
        return service.chat(request, Caller.user(accountId));
    }
    @PostMapping("/summarize")
    public SummaryResponse summarize(@Valid @RequestBody SummaryRequest request, HttpServletRequest servletRequest) {
        requireAccount(servletRequest);
        return new SummaryResponse(service.summarize(request));
    }
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto create(@Valid @RequestBody(required = false) CreateConversationRequest request, HttpServletRequest servletRequest) {
        return service.create(request == null ? new CreateConversationRequest(null) : request, Caller.from(servletRequest, true));
    }
    @GetMapping("/conversations")
    public List<ConversationDto> list(HttpServletRequest servletRequest) {
        return service.list(Caller.from(servletRequest, false));
    }
    @GetMapping("/conversations/{id}")
    public ConversationDto get(@PathVariable UUID id, HttpServletRequest servletRequest) {
        return service.get(id, Caller.from(servletRequest, false));
    }
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest servletRequest) {
        service.delete(id, Caller.from(servletRequest, false));
    }

    private UUID requireAccount(HttpServletRequest request) {
        UUID accountId = com.seekora.account.AccountController.currentAccountId(request);
        if (accountId == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Please sign in to use Seekora search.");
        }
        return accountId;
    }
}
