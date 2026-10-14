package com.seekora.api;

import com.seekora.account.AccountUserRepository;
import com.seekora.ai.AiProviderException;
import com.seekora.ai.AnswerGenerator;
import com.seekora.ai.AnswerGenerator.Turn;
import com.seekora.conversation.ChatMessage;
import com.seekora.conversation.Conversation;
import com.seekora.conversation.ConversationRepository;
import com.seekora.web.ResearchException;
import com.seekora.web.WebResearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.seekora.api.ApiDtos.ChatRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantServiceTest {
    private WebResearchService research;
    private ConversationRepository conversations;
    private AnswerGenerator generator;
    private AssistantService service;
    private WebResearchService.ResearchSource source;
    private final Caller guest = Caller.guest(new HashSet<>());

    @BeforeEach
    void setUp() {
        research = mock(WebResearchService.class);
        conversations = mock(ConversationRepository.class);
        generator = mock(AnswerGenerator.class);
        service = new AssistantService(research, conversations, mock(AccountUserRepository.class), generator,
                mock(PlatformTransactionManager.class), false);
        source = new WebResearchService.ResearchSource("Source", "https://example.org", "A sufficiently detailed source excerpt.");
        when(research.research(anyString())).thenReturn(new WebResearchService.ResearchResult("Local evidence summary", List.of(source)));
        when(conversations.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(generator.modelName()).thenReturn("test-model");
    }

    @Test
    void usesLocalEvidenceSummaryWhenNoProviderIsConfigured() {
        when(generator.isConfigured()).thenReturn(false);
        var response = service.chat(new ChatRequest("question", null), guest);
        assertTrue(response.assistantMessage().content().startsWith("Extractive web summary (AI provider not configured):"));
        assertEquals("extractive", response.answerMode());
        assertEquals("used", response.searchStatus());
        assertTrue(response.notice().contains("No AI model is configured"));
        assertEquals(1, response.sources().size());
        verify(generator, never()).generate(anyString(), anyList(), anyList());
        verify(conversations).save(any(Conversation.class));
    }

    @Test
    void usesConfiguredProviderAndReturnsRetrievedCitations() {
        when(generator.isConfigured()).thenReturn(true);
        when(generator.generate(eq("What causes tides?"), anyList(), anyList())).thenReturn("Generated answer [1]");
        var response = service.chat(new ChatRequest("What causes tides?", null), guest);
        assertEquals("Generated answer [1]", response.assistantMessage().content());
        assertEquals("ai", response.answerMode());
        assertEquals("https://example.org", response.sources().getFirst().url());
        assertTrue(response.notice().contains("test-model"));
        assertTrue(guest.guestConversationIds().contains(response.conversationId()), "the guest session must now own the conversation");
    }

    @Test
    void doesNotPersistAFalseSuccessWhenConfiguredProviderFails() {
        when(generator.isConfigured()).thenReturn(true);
        when(generator.generate(anyString(), anyList(), anyList())).thenThrow(new AiProviderException(HttpStatus.TOO_MANY_REQUESTS, "quota"));
        assertThrows(AiProviderException.class, () -> service.chat(new ChatRequest("question", null), guest));
        verify(conversations, never()).save(any(Conversation.class));
    }

    @Test
    void stillAnswersFromTheModelWhenWebSearchFails() {
        when(generator.isConfigured()).thenReturn(true);
        when(research.research(anyString())).thenThrow(new ResearchException("Web search is temporarily unavailable."));
        when(generator.generate(anyString(), eq(List.of()), anyList())).thenReturn("Model-only answer");
        var response = service.chat(new ChatRequest("What is the speed of light?", null), guest);
        assertEquals("Model-only answer", response.assistantMessage().content());
        assertEquals("unavailable", response.searchStatus());
        assertTrue(response.sources().isEmpty());
        assertTrue(response.notice().contains("not verified"));
    }

    @Test
    void failsClearlyWhenNeitherModelNorSearchCanAnswer() {
        when(generator.isConfigured()).thenReturn(false);
        when(research.research(anyString())).thenThrow(new ResearchException("Web search is temporarily unavailable."));
        assertThrows(ResearchException.class, () -> service.chat(new ChatRequest("question", null), guest));
        verify(conversations, never()).save(any(Conversation.class));
    }

    @Test
    void returnsAConfigurationErrorWhenAiIsRequiredButMissing() {
        var strict = new AssistantService(research, conversations, mock(AccountUserRepository.class), generator,
                mock(PlatformTransactionManager.class), true);
        when(generator.isConfigured()).thenReturn(false);
        AiProviderException error = assertThrows(AiProviderException.class, () -> strict.chat(new ChatRequest("hello there", null), guest));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertTrue(error.getMessage().contains("SEEKORA_AI_API_KEY"));
        verifyNoInteractions(research);
    }

    @Test
    void skipsWebSearchForGreetingsAndPureCodingTasks() {
        when(generator.isConfigured()).thenReturn(true);
        when(generator.generate(anyString(), anyList(), anyList())).thenReturn("fine");
        assertEquals("skipped", service.chat(new ChatRequest("hello!", null), guest).searchStatus());
        assertEquals("skipped", service.chat(new ChatRequest("Write a Java method that reverses a string", null), guest).searchStatus());
        verifyNoInteractions(research);
    }

    @Test
    void followUpUsesStoredHistoryOfTheSameConversation() {
        when(generator.isConfigured()).thenReturn(true);
        UUID id = UUID.randomUUID();
        Conversation existing = new Conversation("Tides");
        existing.addMessage(new ChatMessage("user", "What causes tides?"));
        existing.addMessage(new ChatMessage("assistant", "The Moon's gravity."));
        Caller owner = Caller.guest(new HashSet<>(Set.of(id)));
        when(conversations.findByIdAndUserIsNull(id)).thenReturn(Optional.of(existing));
        when(generator.generate(anyString(), anyList(), anyList())).thenReturn("Also the Sun.");

        service.chat(new ChatRequest("and the sun?", id), owner);

        verify(generator).generate(eq("and the sun?"), anyList(),
                eq(List.of(new Turn("user", "What causes tides?"), new Turn("assistant", "The Moon's gravity."))));
        verify(research).research("What causes tides? and the sun?");
        assertEquals(4, existing.getMessages().size());
    }

    @Test
    void aGuestCannotUseAnotherSessionsConversation() {
        when(generator.isConfigured()).thenReturn(true);
        UUID foreign = UUID.randomUUID();
        assertThrows(NotFoundException.class, () -> service.chat(new ChatRequest("hi", foreign), guest));
        verify(conversations, never()).findByIdAndUserIsNull(any());
    }

    @Test
    void searchPolicyAndQueryBuilding() {
        assertFalse(AssistantService.shouldSearch("hi"));
        assertFalse(AssistantService.shouldSearch("Thanks!"));
        assertFalse(AssistantService.shouldSearch("fix this code:\n```java\nint x;\n```"));
        assertFalse(AssistantService.shouldSearch("Please write a poem about autumn"));
        assertTrue(AssistantService.shouldSearch("What is quantum computing?"));
        assertTrue(AssistantService.shouldSearch("Explain how vaccines work"));
        assertTrue(AssistantService.shouldSearch("Summarize the latest news about the Mars mission"), "freshness keywords force a search");
        assertEquals("a standalone question with more than six words in it",
                AssistantService.searchQuery("a standalone question with more than six words in it", List.of(new Turn("user", "old"))));
        assertEquals("old topic why", AssistantService.searchQuery("why", List.of(new Turn("user", "old topic"), new Turn("assistant", "x"))));
    }
}
