package com.wedding.identity;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("postgres")
public class PostgresMemberRepository implements MemberRepository {
    private final JdbcClient jdbc;
    public PostgresMemberRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public boolean isAdmin(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM iam.user_platform_role r JOIN iam.user_account u ON u.id=r.user_id WHERE r.user_id=:id AND r.role_code='ADMIN' AND u.status='ACTIVE')").param("id",id).query(Boolean.class).single();
    }
    public void grantAdmin(UUID id) {
        jdbc.sql("INSERT INTO iam.user_platform_role(user_id,role_code) VALUES(:id,'ADMIN') ON CONFLICT DO NOTHING").param("id",id).update();
    }
    private static final String SELECT = "SELECT u.id,c.email,u.display_name,c.password_hash,c.email_verified_at IS NOT NULL AS email_verified FROM iam.user_account u JOIN iam.local_credential c ON c.user_id=u.id WHERE u.status='ACTIVE' AND ";
    private Optional<Member> find(String condition, Object value) {
        return jdbc.sql(SELECT+condition).param("value",value).query((rs,n) -> new Member(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),rs.getString("password_hash"),rs.getBoolean("email_verified"))).optional();
    }
    public Optional<Member> byEmail(String email) { return find("c.email=:value",email); }
    public Optional<Member> byId(UUID id) { return find("u.id=:value",id); }
    @Transactional public Member createAdmin(String email, String name, String hash) {
        // One transaction: a role failure must not leave a partially provisioned account.
        var member=create(email,name,hash);grantAdmin(member.id());return member;
    }
    @Transactional public Member create(String email, String name, String hash) {
        UUID id=UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO iam.user_account(id,display_name) VALUES(:id,:name)").param("id",id).param("name",name).update();
            jdbc.sql("INSERT INTO iam.local_credential(user_id,email,password_hash,email_verified_at) VALUES(:id,:email,:hash,now())").param("id",id).param("email",email).param("hash",hash).update();
            jdbc.sql("INSERT INTO iam.auth_identity(user_id,issuer,subject) VALUES(:id,'LOCAL',:subject)").param("id",id).param("subject",id.toString()).update();
        } catch (DuplicateKeyException ex) { throw MemberService.conflict(); }
        return new Member(id,email,name,hash,true);
    }
    @Transactional public Member rename(UUID id, String name) {
        int changed=jdbc.sql("UPDATE iam.user_account SET display_name=:name WHERE id=:id AND status='ACTIVE'").param("name",name).param("id",id).update();
        if (changed!=1) throw MemberService.unauthorized();
        return byId(id).orElseThrow(MemberService::unauthorized);
    }
    public void changePassword(UUID id, String hash) {
        int changed=jdbc.sql("""
            UPDATE iam.local_credential c SET password_hash=:hash,password_changed_at=now()
            FROM iam.user_account u WHERE u.id=c.user_id AND c.user_id=:id AND u.status='ACTIVE'
            """).param("hash",hash).param("id",id).update();
        if (changed!=1) throw MemberService.unauthorized();
    }
    public Optional<java.time.Instant> credentialsChangedAt(UUID id) {
        return jdbc.sql("SELECT c.password_changed_at FROM iam.local_credential c JOIN iam.user_account u ON u.id=c.user_id WHERE c.user_id=:id AND u.status='ACTIVE'")
            .param("id",id).query((rs,n)->rs.getTimestamp(1).toInstant()).optional();
    }
    public Set<String> favorites(UUID id) {
        return new HashSet<>(jdbc.sql("SELECT listing_id::text FROM planning.user_favorite WHERE user_id=:id").param("id",id).query(String.class).list());
    }
    @Transactional public void favorite(UUID id, UUID listingId, boolean saved) {
        boolean active=jdbc.sql("SELECT status='ACTIVE' FROM iam.user_account WHERE id=:id FOR UPDATE").param("id",id).query(Boolean.class).optional().orElse(false);
        if(!active)throw MemberService.unauthorized();
        if(saved){
            boolean full=jdbc.sql("SELECT (SELECT count(*) FROM planning.user_favorite WHERE user_id=:id)>=500 AND NOT EXISTS(SELECT 1 FROM planning.user_favorite WHERE user_id=:id AND listing_id=:listing)")
                .param("id",id).param("listing",listingId).query(Boolean.class).single();
            if(full)throw MemberService.favoriteLimit();
        }
        jdbc.sql(saved ? "INSERT INTO planning.user_favorite(user_id,listing_id) VALUES(:id,:listing) ON CONFLICT DO NOTHING" : "DELETE FROM planning.user_favorite WHERE user_id=:id AND listing_id=:listing")
            .param("id",id).param("listing",listingId).update();
    }
}
