package com.wedding.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One-time mail links, stored only as SHA-256 hashes.
 * Consuming a token and applying its effect happen atomically, so a link can never be used twice.
 */
public interface EmailTokenRepository {
    /** Stores a pending registration. No account exists until {@link #completeRegistration} runs. */
    void saveRegistration(String tokenHash, String email, String displayName, String passwordHash, Instant expiresAt);
    void saveReset(String tokenHash, UUID userId, String email, Instant expiresAt);
    /**
     * Consumes a valid registration token and creates the verified account.
     * Empty when the token is unknown, expired or used. Throws 409 when the email was registered meanwhile.
     */
    Optional<Member> completeRegistration(String tokenHash);
    /** Consumes a valid reset token, stores the new password and invalidates the account's other reset links. */
    Optional<Member> completeReset(String tokenHash, String passwordHash);
}
