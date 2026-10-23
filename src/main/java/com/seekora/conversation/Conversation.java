package com.seekora.conversation;

import com.seekora.account.AccountUser;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversations", indexes = @Index(name = "idx_conversations_user_updated", columnList = "user_id, updatedAt"))
public class Conversation {
    @Id
    private UUID id;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AccountUser user;
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<ChatMessage> messages = new ArrayList<>();

    protected Conversation() { }
    public Conversation(String title) {
        this(title, null);
    }
    public Conversation(String title, AccountUser user) {
        this.id = UUID.randomUUID(); this.title = title; this.user = user;
        this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
    public void addMessage(ChatMessage message) { messages.add(message); message.setConversation(this); updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ChatMessage> getMessages() { return messages; }
}
