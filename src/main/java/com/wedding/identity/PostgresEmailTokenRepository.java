package com.wedding.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
@Profile("postgres")
public class PostgresEmailTokenRepository implements EmailTokenRepository {
    private final JdbcClient jdbc;
    private final MemberRepository members;
    public PostgresEmailTokenRepository(JdbcClient jdbc, MemberRepository members) { this.jdbc=jdbc; this.members=members; }

    @Transactional public void saveRegistration(String tokenHash, String email, String displayName, String passwordHash, Instant expiresAt) {
        purgeExpired();
        jdbc.sql("INSERT INTO iam.email_token(token_hash,purpose,email,display_name,password_hash,expires_at) VALUES(:hash,'REGISTER',:email,:name,:password,:expires)")
            .param("hash",tokenHash).param("email",email).param("name",displayName).param("password",passwordHash).param("expires",Timestamp.from(expiresAt)).update();
    }
    @Transactional public void saveReset(String tokenHash, UUID userId, String email, Instant expiresAt) {
        purgeExpired();
        jdbc.sql("INSERT INTO iam.email_token(token_hash,purpose,email,user_id,expires_at) VALUES(:hash,'RESET_PASSWORD',:email,:user,:expires)")
            .param("hash",tokenHash).param("email",email).param("user",userId).param("expires",Timestamp.from(expiresAt)).update();
    }
    private record Pending(String email, String displayName, String passwordHash, UUID userId) {}
    /** Locks the row so two concurrent clicks on the same link cannot both succeed. */
    private Optional<Pending> consume(String tokenHash, String purpose) {
        var pending=jdbc.sql("""
            SELECT email,display_name,password_hash,user_id FROM iam.email_token
            WHERE token_hash=:hash AND purpose=:purpose AND used_at IS NULL AND expires_at>now() FOR UPDATE
            """).param("hash",tokenHash).param("purpose",purpose)
            .query((rs,n)->new Pending(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class))).optional();
        pending.ifPresent(p->jdbc.sql("UPDATE iam.email_token SET used_at=now() WHERE token_hash=:hash").param("hash",tokenHash).update());
        return pending;
    }
    @Transactional public Optional<Member> completeRegistration(String tokenHash) {
        var pending=consume(tokenHash,"REGISTER");
        if(pending.isEmpty()) return Optional.empty();
        var p=pending.get();
        // Another link for the same address may have completed first; the whole transaction rolls back.
        if(members.byEmail(p.email()).isPresent()) throw alreadyRegistered();
        var member=members.create(p.email(),p.displayName(),p.passwordHash());
        // Other pending registrations for this address can no longer succeed; retire them now.
        jdbc.sql("UPDATE iam.email_token SET used_at=now() WHERE email=:email AND purpose='REGISTER' AND used_at IS NULL").param("email",p.email()).update();
        return Optional.of(member);
    }
    @Transactional public Optional<Member> completeReset(String tokenHash, String passwordHash) {
        var pending=consume(tokenHash,"RESET_PASSWORD");
        if(pending.isEmpty()) return Optional.empty();
        var user=pending.get().userId();
        var member=members.byId(user);
        if(member.isEmpty()) return Optional.empty();
        members.changePassword(user,passwordHash);
        jdbc.sql("UPDATE iam.email_token SET used_at=now() WHERE user_id=:user AND purpose='RESET_PASSWORD' AND used_at IS NULL").param("user",user).update();
        return members.byId(user);
    }
    /** Keeps the table small; a day of grace leaves recently expired rows available for support questions. */
    private void purgeExpired() {
        jdbc.sql("DELETE FROM iam.email_token WHERE expires_at<now()-interval '1 day'").update();
    }
    static ResponseStatusException alreadyRegistered() {
        return new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"이미 가입이 완료된 이메일입니다. 로그인해 주세요.");
    }
}
