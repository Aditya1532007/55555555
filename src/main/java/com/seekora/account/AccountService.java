package com.seekora.account;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.seekora.account.AccountDtos.*;

@Service
public class AccountService {
    private final AccountUserRepository users;
    private final PasswordHasher passwords;
    /** Checked when the email is unknown so that response time does not reveal which emails have accounts. */
    private final String decoyHash;

    public AccountService(AccountUserRepository users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
        this.decoyHash = passwords.hash("seekora-decoy-password");
    }

    @Transactional
    public AccountUser register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) throw new EmailAlreadyExistsException();
        AccountUser user = new AccountUser(request.name().trim(), email, passwords.hash(request.password()));
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyExistsException();
        }
    }

    @Transactional(readOnly = true)
    public AccountUser authenticate(LoginRequest request) {
        AccountUser user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwords.matches(request.password(), decoyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwords.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public AccountUser getById(java.util.UUID id) {
        return users.findById(id).orElseThrow(InvalidCredentialsException::new);
    }

    private String normalizeEmail(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }
}
