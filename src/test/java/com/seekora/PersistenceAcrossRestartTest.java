package com.seekora;

import com.seekora.account.AccountDtos.LoginRequest;
import com.seekora.account.AccountDtos.RegisterRequest;
import com.seekora.account.AccountService;
import com.seekora.account.InvalidCredentialsException;
import com.seekora.api.ApiDtos.CreateConversationRequest;
import com.seekora.api.AssistantService;
import com.seekora.api.Caller;
import com.seekora.api.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Stops and restarts the whole application on the same database file: accounts and conversations must survive. */
class PersistenceAcrossRestartTest {
    @TempDir Path dir;

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(SeekoraApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + dir.resolve("db").toAbsolutePath(),
                        "--spring.jpa.hibernate.ddl-auto=update", "--seekora.ai.api-key=");
    }

    @Test void accountsAndPrivateConversationsSurviveARestart() {
        UUID userId, otherId;
        UUID conversationId;
        try (ConfigurableApplicationContext first = start()) {
            AccountService accounts = first.getBean(AccountService.class);
            userId = accounts.register(new RegisterRequest("Persisted", "persist@example.org", "correct horse battery staple", null)).getId();
            otherId = accounts.register(new RegisterRequest("Other", "other@example.org", "another long password here", null)).getId();
            conversationId = first.getBean(AssistantService.class).create(new CreateConversationRequest("Kept"), Caller.user(userId)).id();
        }
        try (ConfigurableApplicationContext second = start()) {
            AccountService accounts = second.getBean(AccountService.class);
            assertEquals(userId, accounts.authenticate(new LoginRequest("PERSIST@example.org", "correct horse battery staple")).getId());
            assertThrows(InvalidCredentialsException.class, () -> accounts.authenticate(new LoginRequest("persist@example.org", "wrong password value")));
            AssistantService assistant = second.getBean(AssistantService.class);
            assertEquals("Kept", assistant.get(conversationId, Caller.user(userId)).title());
            assertEquals(1, assistant.list(Caller.user(userId)).size());
            assertEquals(0, assistant.list(Caller.user(otherId)).size());
            assertThrows(NotFoundException.class, () -> assistant.get(conversationId, Caller.user(otherId)));
            assertThrows(NotFoundException.class, () -> assistant.delete(conversationId, Caller.user(otherId)));
        }
    }
}
