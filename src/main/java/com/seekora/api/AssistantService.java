package com.seekora.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekora.account.AccountDtos.AccountResponse;
import com.seekora.account.AccountUserRepository;
import com.seekora.ai.AiProviderException;
import com.seekora.ai.AnswerGenerator;
import com.seekora.ai.AnswerGenerator.Turn;
import com.seekora.conversation.ChatMessage;
import com.seekora.conversation.Conversation;
import com.seekora.conversation.ConversationRepository;
import com.seekora.web.LocalSummarizer;
import com.seekora.web.ResearchException;
import com.seekora.web.WebResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.seekora.api.ApiDtos.*;

@Service
public class AssistantService {
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern GREETING = Pattern.compile(
            "^(hi|hello|hey|hiya|yo|thanks|thank you|thx|ok|okay|cool|great|nice|bye|goodbye|good (morning|afternoon|evening|night))[\\s!.?,]*$");
    private static final Pattern TASK_REQUEST = Pattern.compile(
            "^(please\\s+)?(write|create|generate|implement|refactor|fix|debug|convert|translate|rewrite|proofread|summari[sz]e|draft|compose|brainstorm|optimi[sz]e|review|format)\\b");
    private static final Pattern NEEDS_FRESH_DATA = Pattern.compile(
            "\\b(latest|news|today|tonight|current|currently|recent|recently|this week|this month|this year|right now|price of|score|weather|20[2-9][0-9])\\b");

    private final WebResearchService research;
    private final ConversationRepository conversations;
    private final AccountUserRepository users;
    private final AnswerGenerator answerGenerator;
    private final TransactionTemplate tx;
    private final boolean aiRequired;

    public AssistantService(WebResearchService research, ConversationRepository conversations,
                            AccountUserRepository users, AnswerGenerator answerGenerator,
                            PlatformTransactionManager transactionManager,
                            @Value("${seekora.ai.required:false}") boolean aiRequired) {
        this.research = research; this.conversations = conversations; this.users = users;
        this.answerGenerator = answerGenerator; this.aiRequired = aiRequired;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Not transactional as a whole on purpose: web research and the AI call can take a minute, and holding a
     * database transaction (and pooled connection) open that long would starve other requests. Only the short
     * read and write steps run in their own transactions.
     */
    public ChatResponse chat(ChatRequest request, Caller caller) {
        String message = request.message().trim();
        boolean ai = answerGenerator.isConfigured();
        if (!ai && aiRequired) {
            throw new AiProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI answers are not configured. Set SEEKORA_AI_API_KEY (or SEEKORA_AI_BASE_URL for a local model) and restart Seekora.");
        }

        List<Turn> history = request.conversationId() == null ? List.of()
                : Objects.requireNonNull(tx.execute(txStatus -> loadHistory(request.conversationId(), caller)));
        ChatMessage userMessage = new ChatMessage("user", message);

        WebResearchService.ResearchResult evidence = null;
        String searchStatus;
        if (ai && !shouldSearch(message)) {
            searchStatus = "skipped";
        } else {
            try {
                evidence = research.research(searchQuery(message, history));
                searchStatus = "used";
            } catch (ResearchException ex) {
                if (!ai) throw ex; // nothing else can answer without a model
                log.warn("Web research failed; answering from the AI model alone: {}", ex.getMessage());
                searchStatus = "unavailable";
            }
        }
        List<WebResearchService.ResearchSource> sources = evidence == null ? List.of() : evidence.sources();

        String answer;
        String notice;
        if (ai) {
            answer = answerGenerator.generate(message, sources, history);
            String model = answerGenerator.modelName();
            notice = switch (searchStatus) {
                case "used" -> "Generated by " + model + " from the linked web pages. AI can make mistakes; check the sources for anything important.";
                case "skipped" -> "Answered by " + model + " from its general knowledge. No web sources were used and nothing here was verified.";
                default -> "Web search was unavailable, so this answer comes from " + model + "'s general knowledge alone and was not verified against any source.";
            };
        } else {
            answer = "Extractive web summary (AI provider not configured):\n\n" + evidence.answer();
            notice = "No AI model is configured on this server, so this is an automatic extract of sentences from the linked pages, not a generated answer. See the README to enable AI answers.";
        }
        final String mode = ai ? "ai" : "extractive";
        final String status = searchStatus;
        final String answerNotice = notice;

        List<SourceDto> sourceDtos = sources.stream()
                .map(s -> new SourceDto(s.title(), s.url(), s.text().substring(0, Math.min(s.text().length(), 360)))).toList();
        ChatMessage assistantMessage = new ChatMessage("assistant", answer);
        assistantMessage.setAnswerMetadata(writeJson(sourceDtos), mode);

        return Objects.requireNonNull(tx.execute(txStatus -> {
            Conversation conversation = request.conversationId() == null
                    ? new Conversation(titleFrom(message), caller.isGuest() ? null : users.getReferenceById(caller.userId()))
                    : findConversation(request.conversationId(), caller);
            conversation.addMessage(userMessage);
            conversation.addMessage(assistantMessage);
            conversations.save(conversation);
            if (caller.isGuest()) caller.guestConversationIds().add(conversation.getId());
            return new ChatResponse(conversation.getId(), conversation.getTitle(), toDto(userMessage),
                    toDto(assistantMessage), sourceDtos, mode, status, answerNotice);
        }));
    }

    @Transactional(readOnly = true)
    public StatusResponse status(UUID userId) {
        AccountResponse account = userId == null ? null : users.findById(userId)
                .map(u -> new AccountResponse(u.getId(), u.getName(), u.getEmail(), u.getCreatedAt())).orElse(null);
        return new StatusResponse(answerGenerator.isConfigured(), aiRequired, answerGenerator.modelName(),
                answerGenerator.providerLabel(), account);
    }

    @Transactional(readOnly = true)
    public ConversationDto get(UUID id, Caller caller) { return toDto(findConversation(id, caller)); }

    @Transactional(readOnly = true)
    public List<ConversationDto> list(Caller caller) {
        List<Conversation> owned;
        if (!caller.isGuest()) {
            owned = conversations.findAllByUser_IdOrderByUpdatedAtDesc(caller.userId());
        } else if (caller.guestConversationIds().isEmpty()) {
            owned = List.of();
        } else {
            owned = conversations.findAllByIdInAndUserIsNullOrderByUpdatedAtDesc(List.copyOf(caller.guestConversationIds()));
        }
        // Summaries only: loading every message of every conversation just to draw a list is wasteful.
        return owned.stream().map(c -> new ConversationDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(), null)).toList();
    }

    @Transactional
    public ConversationDto create(CreateConversationRequest request, Caller caller) {
        String title = request.title() == null || request.title().isBlank() ? "New conversation" : request.title().trim();
        Conversation saved = conversations.save(new Conversation(title, caller.isGuest() ? null : users.getReferenceById(caller.userId())));
        if (caller.isGuest()) caller.guestConversationIds().add(saved.getId());
        return toDto(saved);
    }

    @Transactional
    public void delete(UUID id, Caller caller) {
        conversations.delete(findConversation(id, caller));
        if (caller.isGuest()) caller.guestConversationIds().remove(id);
    }

    public String summarize(SummaryRequest request) {
        return LocalSummarizer.summarizeText(request.text(), request.instruction());
    }

    // ------------------------------------------------------------------ helpers

    private List<Turn> loadHistory(UUID id, Caller caller) {
        return findConversation(id, caller).getMessages().stream().map(m -> new Turn(m.getRole(), m.getContent())).toList();
    }

    private Conversation findConversation(UUID id, Caller caller) {
        if (caller.isGuest()) {
            if (!caller.guestConversationIds().contains(id)) throw new NotFoundException("Conversation not found");
            return conversations.findByIdAndUserIsNull(id).orElseThrow(() -> new NotFoundException("Conversation not found"));
        }
        return conversations.findByIdAndUser_Id(id, caller.userId()).orElseThrow(() -> new NotFoundException("Conversation not found"));
    }

    /**
     * Whether a web search is worth running before asking the model. Greetings and pure writing/coding tasks
     * do not benefit from it (and would only add latency and irrelevant sources). Without a model, search is
     * always required because it is the only source of an answer.
     */
    static boolean shouldSearch(String message) {
        String lower = message.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("```") || GREETING.matcher(lower).matches()) return false;
        return !TASK_REQUEST.matcher(lower).find() || NEEDS_FRESH_DATA.matcher(lower).find();
    }

    /** Short follow-ups such as "why?" are useless as search queries, so they borrow the previous question. */
    static String searchQuery(String message, List<Turn> history) {
        String query = message.replaceAll("\\s+", " ").trim();
        if (query.split(" ").length <= 6) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).role())) {
                    String previous = history.get(i).content().replaceAll("\\s+", " ").trim();
                    return (previous.length() > 120 ? previous.substring(0, 120) : previous) + " " + query;
                }
            }
        }
        return query;
    }

    private MessageDto toDto(ChatMessage m) {
        return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt(), readSources(m.getSourcesJson()), m.getAnswerMode());
    }

    private ConversationDto toDto(Conversation c) {
        return new ConversationDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(), c.getMessages().stream().map(this::toDto).toList());
    }

    private static String writeJson(List<SourceDto> sources) {
        try { return JSON.writeValueAsString(sources); }
        catch (JsonProcessingException e) { return "[]"; }
    }

    private static List<SourceDto> readSources(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, new TypeReference<List<SourceDto>>() { }); }
        catch (JsonProcessingException e) { return null; }
    }

    private String titleFrom(String text) {
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() > 60 ? normalized.substring(0, 57) + "..." : normalized;
    }
}
