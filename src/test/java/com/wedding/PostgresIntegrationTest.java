package com.wedding;

import com.wedding.catalog.VenueRepository;
import com.wedding.catalog.FavoriteListingRepository;
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
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="WEDDING_POSTGRES_TESTS", matches="true")
class PostgresIntegrationTest {
    @Autowired MemberRepository members;
    @Autowired com.wedding.identity.MemberService memberService;
    @Autowired FavoriteListingRepository favoriteListings;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired CatalogDraftRepository drafts;
    @Autowired VenueRepository venues;
    @Autowired JdbcClient jdbc;
    @Autowired PublicationRepository publications;
    @Autowired javax.sql.DataSource dataSource;
    @Test @org.springframework.transaction.annotation.Transactional
    void syntheticFixtureRemainsVisibleAfterRecheckAndFiltersRunInDatabase() {
        jdbc.sql("UPDATE pricing.offer_revision SET verified_at=now()-interval '92 days',recheck_after=now()-interval '1 day' WHERE terms_snapshot @> '{\"demo\":true}'::jsonb").update();
        assertEquals(4,venues.findAll().size());
        assertEquals(1,venues.candidates("강남","호텔","오브").size());
        assertEquals(0,venues.candidates("강남","가든","").size());
        assertTrue(venues.existsPublished(UUID.fromString("10000000-0000-4000-8000-000000000001")));
        assertFalse(venues.existsPublished(UUID.randomUUID()));
    }
    @Test void runtimeRoleCanReadButCannotChangeSchemaOrRewriteAudit() throws Exception {
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='wedding_app') THEN CREATE ROLE wedding_app NOLOGIN; END IF; END $$");
            DatabaseMigration.grantRuntimeAccess(connection);
            statement.execute("SET ROLE wedding_app");
            try {
                try(var rows=statement.executeQuery("SELECT count(*) FROM search.demo_venue_projection")) {assertTrue(rows.next());assertEquals(4,rows.getInt(1));}
                assertEquals("42501",assertThrows(java.sql.SQLException.class,()->statement.execute("CREATE TABLE iam.forbidden_runtime_table(id integer)")).getSQLState());
                assertEquals("42501",assertThrows(java.sql.SQLException.class,()->statement.execute("DELETE FROM ops.audit_event WHERE false")).getSQLState());
                assertEquals("42501",assertThrows(java.sql.SQLException.class,()->statement.execute("ALTER TABLE iam.user_account ADD COLUMN forbidden_runtime_column text")).getSQLState());
            } finally {statement.execute("RESET ROLE");}
        }
    }
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

    private CatalogData publishable() {
        var data=data();
        return new CatalogData(data.externalKey(),data.organizationName(),data.branchName(),CatalogData.Category.WEDDING_VIDEO,data.region(),data.address(),"","https://example.test/official");
    }
    @Test void publicationKeepsApprovedSnapshotUntilExplicitRepublishAndWithdraw() {
        var actor=admin();var data=publishable();var draft=drafts.create(actor,data);var date=java.time.LocalDate.now();
        assertEquals(0,publications.list(0,"",data.externalKey(),true).total());
        var first=publications.publish(actor,draft.id(),0,null,data,date);
        assertEquals(1,publications.list(0,"WEDDING_VIDEO",data.externalKey(),true).total());
        var changed=new CatalogData(data.externalKey(),"수정된 이름",data.branchName(),data.category(),data.region(),data.address(),"",data.sourceUrl());
        var updated=drafts.update(actor,draft.id(),0,changed);
        assertEquals(data.organizationName(),publications.get(draft.id()).orElseThrow().data().organizationName());
        assertThrows(ResponseStatusException.class,()->publications.publish(actor,draft.id(),0,first.version(),data,date));
        var second=publications.publish(actor,draft.id(),updated.version(),first.version(),changed,date);
        assertEquals(first.listingId(),second.listingId());assertEquals("수정된 이름",second.data().organizationName());
        assertEquals(2,jdbc.sql("SELECT count(*) FROM catalog.listing_fact WHERE listing_id=:id").param("id",first.listingId()).query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT count(*) FROM catalog.listing_fact WHERE listing_id=:id AND status='APPROVED'").param("id",first.listingId()).query(Integer.class).single());
        assertThrows(ResponseStatusException.class,()->publications.withdraw(actor,draft.id(),first.version()));
        publications.withdraw(actor,draft.id(),second.version());
        assertEquals("SUSPENDED",publications.get(draft.id()).orElseThrow().status());
        assertEquals(0,publications.list(0,"",data.externalKey(),true).total());
    }
    @Test void concurrentPublicationRejectsStaleRequestWithoutDuplicateCanonicalRows() throws Exception {
        var actor=admin();var data=publishable();var draft=drafts.create(actor,data);
        java.util.concurrent.Callable<Boolean> publish=()->{try{publications.publish(actor,draft.id(),0,null,data,java.time.LocalDate.now());return true;}catch(ResponseStatusException ex){assertEquals(409,ex.getStatusCode().value());return false;}};
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(publish);var b=executor.submit(publish);
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.sql("SELECT count(*) FROM ops.audit_event WHERE target_id=:id AND action='CATALOG_PUBLISH'").param("id",draft.id()).query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT count(*) FROM catalog.listing WHERE slug=:slug").param("slug","catalog-"+draft.id()).query(Integer.class).single());
    }
    @Test void publicationFailureRollsBackSourceAndCanonicalRecords() {
        var actor=admin();var data=publishable();var draft=drafts.create(actor,data);
        assertThrows(org.springframework.dao.DataAccessException.class,()->publications.publish(UUID.randomUUID(),draft.id(),0,null,data,java.time.LocalDate.now()));
        assertTrue(publications.get(draft.id()).isEmpty());
        assertEquals(0,jdbc.sql("SELECT count(*) FROM catalog.listing WHERE slug=:slug").param("slug","catalog-"+draft.id()).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT count(*) FROM partner.organization WHERE display_name=:name").param("name",data.organizationName()).query(Integer.class).single());
    }
    @Test void realPublishedFavoritesAreAccountBoundAndWithdrawnDataIsHidden() throws Exception {
        var actor=admin();var data=publishable();var draft=drafts.create(actor,data);
        var publication=publications.publish(actor,draft.id(),0,null,data,java.time.LocalDate.now());
        var alice=members.create(UUID.randomUUID()+"@example.test","Alice","hash").id();
        var bob=members.create(UUID.randomUUID()+"@example.test","Bob","hash").id();
        assertTrue(favoriteListings.existsPublished(publication.listingId()));
        memberService.favorite(alice,publication.listingId(),true);
        memberService.favorite(alice,publication.listingId(),true);
        assertEquals(1,publications.list(0,"","",true,alice).total());
        assertEquals(0,publications.list(0,"","",true,bob).total());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/directory").param("userId",alice.toString()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(bob.toString())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.total").value(0));
        publications.withdraw(actor,draft.id(),publication.version());
        assertFalse(favoriteListings.existsPublished(publication.listingId()));
        assertEquals(0,publications.list(0,"","",true,alice).total());
        assertThrows(ResponseStatusException.class,()->memberService.favorite(bob,publication.listingId(),true));
        assertTrue(memberService.favorite(alice,publication.listingId(),false).isEmpty());
    }
    @Test void concurrentFavoriteWritesCannotExceedTheAccountLimit() throws Exception {
        var actor=members.create(UUID.randomUUID()+"@example.test","찜 테스트","hash").id();
        jdbc.sql("""
            WITH inserted AS (
              INSERT INTO catalog.listing(branch_id,category_code,name,slug)
              SELECT (SELECT branch_id FROM catalog.listing LIMIT 1),'VENUE','CI limit fixture',CAST(:user AS text)||'-limit-'||n
              FROM generate_series(1,499) n RETURNING id
            ) INSERT INTO planning.user_favorite(user_id,listing_id) SELECT :user,id FROM inserted
            """).param("user",actor).update();
        var a=UUID.fromString("10000000-0000-4000-8000-000000000001");
        var b=UUID.fromString("10000000-0000-4000-8000-000000000002");
        java.util.function.Function<UUID,Boolean> save=id->{try{members.favorite(actor,id,true);return true;}catch(ResponseStatusException ex){assertEquals(429,ex.getStatusCode().value());return false;}};
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->save.apply(a));var second=executor.submit(()->save.apply(b));
            assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(500,members.favorites(actor).size());
        var existing=members.favorites(actor).iterator().next();members.favorite(actor,UUID.fromString(existing),true);
        assertEquals(500,members.favorites(actor).size());
    }
    @Test void deployedDatabaseGuardRejectsTheDisposableDatabaseBeforeAnyWrites() throws Exception {
        try(var connection=dataSource.getConnection()){
            assertThrows(IllegalStateException.class,()->DatabaseIdentity.require(connection,"wedding_app"));
        }
    }
}
