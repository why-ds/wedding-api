package com.wedding.identity;

import java.util.UUID;

/** Internal credential record. Never serialize this object into a response or log. */
public record Member(UUID id, String email, String displayName, String passwordHash) {
    public Profile profile() { return profile(false); }
    public Profile profile(boolean admin) { return new Profile(id, email, displayName, false, admin); }
    public record Profile(UUID id, String email, String displayName, boolean emailVerified, boolean admin) {}
}
