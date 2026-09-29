package com.wedding.identity;

import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** In-memory twin of the PostgreSQL rules for the demo profile; cleared on restart. */
@Repository
@Profile("demo")
public class DemoEmailTokenRepository implements EmailTokenRepository {
    private record Token(String purpose, String email, UUID userId, String displayName, String passwordHash, Instant expiresAt, boolean used) {
        Token spent() { return new Token(purpose,email,userId,displayName,passwordHash,expiresAt,true); }
    }
    private final Map<String,Token> tokens=new HashMap<>();
    private final MemberRepository members;
    public DemoEmailTokenRepository(MemberRepository members) { this.members=members; }

    public synchronized void saveRegistration(String tokenHash, String email, String displayName, String passwordHash, Instant expiresAt) {
        purgeExpired();
        tokens.put(tokenHash,new Token("REGISTER",email,null,displayName,passwordHash,expiresAt,false));
    }
    public synchronized void saveReset(String tokenHash, UUID userId, String email, Instant expiresAt) {
        purgeExpired();
        tokens.put(tokenHash,new Token("RESET_PASSWORD",email,userId,null,null,expiresAt,false));
    }
    private Optional<Token> consume(String tokenHash, String purpose) {
        var token=tokens.get(tokenHash);
        if(token==null||token.used()||!token.purpose().equals(purpose)||!token.expiresAt().isAfter(Instant.now())) return Optional.empty();
        tokens.put(tokenHash,token.spent());
        return Optional.of(token);
    }
    public synchronized Optional<Member> completeRegistration(String tokenHash) {
        var token=tokens.get(tokenHash);
        // Checked before consuming so a conflict leaves the token as it was, matching the PostgreSQL rollback.
        if(token!=null&&!token.used()&&members.byEmail(token.email()).isPresent()) throw PostgresEmailTokenRepository.alreadyRegistered();
        var pending=consume(tokenHash,"REGISTER");
        if(pending.isEmpty()) return Optional.empty();
        var p=pending.get();
        var member=members.create(p.email(),p.displayName(),p.passwordHash());
        retire(t->t.purpose().equals("REGISTER")&&t.email().equals(p.email()));
        return Optional.of(member);
    }
    public synchronized Optional<Member> completeReset(String tokenHash, String passwordHash) {
        var pending=consume(tokenHash,"RESET_PASSWORD");
        if(pending.isEmpty()||members.byId(pending.get().userId()).isEmpty()) return Optional.empty();
        var user=pending.get().userId();
        members.changePassword(user,passwordHash);
        retire(t->t.purpose().equals("RESET_PASSWORD")&&user.equals(t.userId()));
        return members.byId(user);
    }
    private void retire(java.util.function.Predicate<Token> match) {
        tokens.replaceAll((hash,token)->!token.used()&&match.test(token)?token.spent():token);
    }
    private void purgeExpired() {
        var cutoff=Instant.now().minusSeconds(86_400);
        tokens.values().removeIf(t->t.expiresAt().isBefore(cutoff));
    }
}
