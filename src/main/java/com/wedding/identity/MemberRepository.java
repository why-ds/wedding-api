package com.wedding.identity;

import java.time.Instant;
import java.util.*;

public interface MemberRepository {
    Optional<Member> byEmail(String email);
    Optional<Member> byId(UUID id);
    /** Creates an account whose email is already proven (registration link or operator provisioning). */
    Member create(String email, String displayName, String passwordHash);
    Member createAdmin(String email, String displayName, String passwordHash);
    Member rename(UUID id, String name);
    /** Replaces the password and moves the credential change time, which ends older sessions. */
    void changePassword(UUID id, String passwordHash);
    /** Last password change of an active account; empty when the account is missing or inactive. */
    Optional<Instant> credentialsChangedAt(UUID id);
    Set<String> favorites(UUID id);
    void favorite(UUID id, UUID listingId, boolean saved);
    boolean isAdmin(UUID id);
    void grantAdmin(UUID id);
}
