package com.wedding.identity;

import java.util.*;

public interface MemberRepository {
    Optional<Member> byEmail(String email);
    Optional<Member> byId(UUID id);
    Member create(String email, String displayName, String passwordHash);
    Member rename(UUID id, String name);
    Set<String> favorites(UUID id);
    void favorite(UUID id, UUID listingId, boolean saved);
    boolean isAdmin(UUID id);
    void grantAdmin(UUID id);
}
