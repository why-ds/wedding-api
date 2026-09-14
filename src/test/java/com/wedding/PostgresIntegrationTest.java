package com.wedding;

import com.wedding.catalog.VenueRepository;
import com.wedding.identity.MemberRepository;
import com.wedding.operations.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against a disposable CI database, never the application DB_URL. */
@SpringBootTest(properties={
    "wedding.admin.bootstrap-enabled=false",
    "spring.datasource.url=${WEDDING_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/wedding_ci}",
    "spring.datasource.username=wedding_ci",
    "spring.datasource.password=ci-only-disposable-password"
})
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named="WEDDING_POSTGRES_TESTS", matches="true")
class PostgresIntegrationTest {
    @Autowired MemberRepository members;
    @Autowired CatalogDraftRepository drafts;
    @Autowired VenueRepository venues;
    @Autowired JdbcClient jdbc;
    private UUID admin() {
        return members.createAdmin(UUID.randomUUID()+"@example.test","CI 관리자","not-a-login-hash").id();
    }
    private CatalogData data() {
        var key="ci-"+UUID.randomUUID();
        return new CatalogData(key,"CI 가상 업체 "+key,"본점",CatalogData.Category.STUDIO,"서울","CI 테스트 주소","","");
    }
    @Test void migrationsAndBothCatalogAndMemberRepositoriesWorkOnPostgres() {
        assertEquals("wedding_ci",jdbc.sql("SELECT current_database()").query(String.class).single());
        assertEquals(4,venues.findAll().size());
        var actor=admin(); assertTrue(members.isAdmin(actor));
        var created=drafts.create(actor,data());
        var updated=drafts.update(actor,created.id(),created.version(),created.data());
        assertEquals(created.version()+1,updated.version());
        assertThrows(ResponseStatusException.class,()->drafts.archive(actor,created.id(),created.version()));
        assertEquals("ARCHIVED",drafts.archive(actor,created.id(),updated.version()).status());
    }
    @Test void databaseConflictRollsBackEveryInsertedRowAndAudit() {
        var actor=admin(); var first=data(); var duplicate=data();
        var ticket=drafts.preview(actor,"0".repeat(64),List.of(first,duplicate));
        drafts.create(actor,duplicate);
        var failure=assertThrows(ResponseStatusException.class,()->drafts.commit(actor,ticket.id()));
        assertEquals(409,failure.getStatusCode().value());
        assertFalse(drafts.conflicts(first,null));
        assertEquals(0,jdbc.sql("SELECT count(*) FROM ops.audit_event WHERE actor_user_id=:id AND action='CATALOG_IMPORT'").param("id",actor).query(Integer.class).single());
    }
    @Test void concurrentCommitRequestsProduceExactlyOneSetOfRows() throws Exception {
        var actor=admin(); var ticket=drafts.preview(actor,"0".repeat(64),List.of(data()));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->drafts.commit(actor,ticket.id()));
            var second=executor.submit(()->drafts.commit(actor,ticket.id()));
            assertEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.sql("SELECT count(*) FROM ops.audit_event WHERE actor_user_id=:id AND action='CATALOG_IMPORT'").param("id",actor).query(Integer.class).single());
    }
}
