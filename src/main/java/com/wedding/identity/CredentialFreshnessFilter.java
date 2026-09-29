package com.wedding.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session that signed in before the account's latest password change, or whose account is no
 * longer active. Sessions live in server memory, so this is how a reset on one device logs out the others.
 * Costs one indexed lookup per request that carries a signed-in session.
 */
public class CredentialFreshnessFilter extends OncePerRequestFilter {
    /** Set by MemberController at sign-in. */
    public static final String AUTHENTICATED_AT="wedding.authenticatedAt";
    private final MemberRepository members;
    public CredentialFreshnessFilter(MemberRepository members) { this.members=members; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session=request.getSession(false);
        if(session!=null && session.getAttribute(AUTHENTICATED_AT) instanceof Instant signedIn) {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if(authentication!=null && authentication.isAuthenticated() && stale(authentication.getName(),signedIn)) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request,response);
    }
    private boolean stale(String principal, Instant signedIn) {
        try {
            var changed=members.credentialsChangedAt(UUID.fromString(principal));
            return changed.isEmpty() || changed.get().isAfter(signedIn);
        } catch(IllegalArgumentException ex) { return true; }
    }
}
