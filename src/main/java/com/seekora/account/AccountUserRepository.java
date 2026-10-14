package com.seekora.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AccountUserRepository extends JpaRepository<AccountUser, UUID> {
    Optional<AccountUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
