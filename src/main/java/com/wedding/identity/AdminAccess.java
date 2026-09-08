package com.wedding.identity;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Always checks current storage; revocation is not delayed until session expiry. */
@Component("adminAccess")
public class AdminAccess {
    private final MemberRepository members;
    public AdminAccess(MemberRepository members) { this.members=members; }
    public boolean allowed(Authentication authentication) {
        if(authentication==null||!authentication.isAuthenticated()) return false;
        try { return members.isAdmin(UUID.fromString(authentication.getName())); }
        catch(IllegalArgumentException ex) { return false; }
    }
}
