package com.seekora.conversation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;
    @Column(nullable = false, length = 16)
    private String role;
    @Column(nullable = false, columnDefinition = "CLOB")
    private String content;
    @Column(nullable = false)
    private Instant createdAt;
    /** JSON array of the sources used for an assistant answer (null for user messages and older rows). */
    @Column(columnDefinition = "CLOB")
    private String sourcesJson;
    /** "ai" or "extractive" for assistant answers (null for user messages and older rows). */
    @Column(length = 16)
    private String answerMode;

    protected ChatMessage() { }
    public ChatMessage(String role, String content) { this.id = UUID.randomUUID(); this.role = role; this.content = content; this.createdAt = Instant.now(); }
    void setConversation(Conversation conversation) { this.conversation = conversation; }
    public void setAnswerMetadata(String sourcesJson, String answerMode) { this.sourcesJson = sourcesJson; this.answerMode = answerMode; }
    public UUID getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSourcesJson() { return sourcesJson; }
    public String getAnswerMode() { return answerMode; }
}
