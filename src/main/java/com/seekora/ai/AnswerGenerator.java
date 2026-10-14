package com.seekora.ai;

import com.seekora.web.WebResearchService;
import java.util.List;

/** Provider-neutral interface for generating an answer, optionally grounded in retrieved web evidence. */
public interface AnswerGenerator {
    /** One earlier turn of the conversation ({@code role} is "user" or "assistant"). */
    record Turn(String role, String content) { }

    /** True when a provider can actually be called (an API key is set, or a keyless local server is configured). */
    boolean isConfigured();

    /** Model identifier shown in the UI (never a secret). */
    default String modelName() { return "unknown"; }

    /** Host of the provider endpoint, for display only (never includes credentials). */
    default String providerLabel() { return ""; }

    /**
     * Generates an answer. {@code sources} may be empty, in which case the model answers from general knowledge
     * and the caller must not present the result as source-verified.
     */
    String generate(String question, List<WebResearchService.ResearchSource> sources, List<Turn> history);
}
