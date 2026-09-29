package com.wedding.operations;

import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository @Profile("postgres")
public class PostgresPublicationRepository implements PublicationRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public PostgresPublicationRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    private static final String FROM="""
        FROM ops.catalog_publication p JOIN catalog.listing l ON l.id=p.listing_id
        JOIN partner.branch b ON b.id=l.branch_id JOIN partner.organization o ON o.id=b.organization_id
        """;
    private final RowMapper<Entry> mapper=(rs,n)->new Entry(rs.getObject("draft_id",UUID.class),rs.getObject("listing_id",UUID.class),json.readValue(rs.getString("approved_data"),CatalogData.class),rs.getString("status"),rs.getLong("draft_version"),rs.getLong("version"),rs.getObject("reviewed_on",LocalDate.class),rs.getTimestamp("updated_at").toInstant());
    public Page list(int page,String category,String query,boolean publishedOnly,UUID favoriteUser){
        String where=" WHERE (NOT :public OR (l.status='PUBLISHED' AND b.status='ACTIVE' AND o.status='ACTIVE')) AND (:category='' OR l.category_code=:category)";
        // Appended only when present: a generic prepared plan cannot use ix_listing_trgm through "(:query='' OR ...)",
        // and position()/lower() on the column never matches the gin_trgm_ops index. ILIKE does.
        boolean text=!query.isEmpty();
        if(text) where+=" AND l.search_text ILIKE :pattern ESCAPE '\\'";
        where+=" AND (NOT :saved OR EXISTS(SELECT 1 FROM planning.user_favorite f WHERE f.listing_id=l.id AND f.user_id=:member))";
        UUID member=favoriteUser==null?new UUID(0,0):favoriteUser;
        var rowsSpec=jdbc.sql("SELECT p.*,l.status "+FROM+where+" ORDER BY p.updated_at DESC,p.draft_id LIMIT 20 OFFSET :offset")
            .param("saved",favoriteUser!=null).param("member",member)
            .param("public",publishedOnly).param("category",category).param("offset",page*20);
        var totalSpec=jdbc.sql("SELECT count(*) "+FROM+where).param("saved",favoriteUser!=null).param("member",member).param("public",publishedOnly).param("category",category);
        if(text){rowsSpec=rowsSpec.param("pattern",contains(query));totalSpec=totalSpec.param("pattern",contains(query));}
        return new Page(rowsSpec.query(mapper).list(),totalSpec.query(Long.class).single(),page,true);
    }
    /** Substring pattern with LIKE metacharacters escaped, so "50%" or "a_b" match literally. */
    public static String contains(String query){
        return "%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    }
    public Optional<Entry> get(UUID draft){return jdbc.sql("SELECT p.*,l.status "+FROM+" WHERE p.draft_id=:id").param("id",draft).query(mapper).optional();}
    public Optional<Entry> findPublished(UUID listing){return jdbc.sql("SELECT p.*,l.status "+FROM+" WHERE p.listing_id=:id AND l.status='PUBLISHED' AND b.status='ACTIVE' AND o.status='ACTIVE'").param("id",listing).query(mapper).optional();}
    private void lockDraft(UUID draft){
        jdbc.sql("SELECT id FROM ops.catalog_draft WHERE id=:id FOR UPDATE").param("id",draft).query(UUID.class).optional().orElseThrow(CatalogIntakeService::missing);
    }
    @Transactional public Entry publish(UUID actor,UUID draft,long draftVersion,Long publicationVersion,CatalogData data,LocalDate reviewedOn){
        lockDraft(draft);
        boolean current=jdbc.sql("SELECT version=:version AND status='DRAFT' FROM ops.catalog_draft WHERE id=:id").param("version",draftVersion).param("id",draft).query(Boolean.class).single();
        if(!current)throw CatalogIntakeService.stale();
        var previous=get(draft);
        if(previous.isPresent()?(publicationVersion==null||previous.get().version()!=publicationVersion):publicationVersion!=null)throw CatalogIntakeService.stale();
        if(previous.isPresent()&&previous.get().data().category()!=data.category())throw CatalogIntakeService.bad("게시 이력이 있는 업체의 업종은 변경할 수 없습니다. 별도 업종 초안을 등록해 주세요.");
        UUID listing,branch,organization;
        if(previous.isEmpty()){
            listing=UUID.randomUUID();branch=UUID.randomUUID();organization=UUID.randomUUID();
            jdbc.sql("INSERT INTO partner.organization(id,legal_name,display_name) VALUES(:id,:name,:name)").param("id",organization).param("name",data.organizationName()).update();
            jdbc.sql("INSERT INTO partner.branch(id,organization_id,name,region_code,road_address,public_phone) VALUES(:id,:org,:name,:region,:address,:phone)")
                .param("id",branch).param("org",organization).param("name",data.branchName()).param("region",data.region()).param("address",data.address()).param("phone",data.publicPhone()).update();
            jdbc.sql("INSERT INTO catalog.listing(id,branch_id,category_code,name,slug) VALUES(:id,:branch,:category,:name,:slug)")
                .param("id",listing).param("branch",branch).param("category",data.category().name()).param("name",data.organizationName()).param("slug","catalog-"+draft).update();
        }else{
            listing=previous.get().listingId();
            branch=jdbc.sql("SELECT branch_id FROM catalog.listing WHERE id=:id FOR UPDATE").param("id",listing).query(UUID.class).single();
            organization=jdbc.sql("SELECT organization_id FROM partner.branch WHERE id=:id").param("id",branch).query(UUID.class).single();
            // These provisional identities are private to this intake entry; grouping is a separate verified workflow.
            jdbc.sql("UPDATE partner.organization SET display_name=:name WHERE id=:id").param("name",data.organizationName()).param("id",organization).update();
            jdbc.sql("UPDATE partner.branch SET name=:name,region_code=:region,road_address=:address,public_phone=:phone WHERE id=:id")
                .param("name",data.branchName()).param("region",data.region()).param("address",data.address()).param("phone",data.publicPhone()).param("id",branch).update();
        }
        UUID source=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO ops.source_document(id,source_kind,title,source_url,observed_at,rights_basis,visibility,uploaded_by)
            VALUES(:id,'EDITOR',:title,:url,:observed,'LINK_ONLY','PUBLIC_FACTS',:actor)
            """).param("id",source).param("title",data.organizationName()+" 기본 정보 출처").param("url",data.sourceUrl())
            .param("observed",java.sql.Timestamp.from(reviewedOn.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant())).param("actor",actor).update();
        String snapshot=json.writeValueAsString(data);
        jdbc.sql("UPDATE catalog.listing_fact SET status='SUPERSEDED' WHERE listing_id=:id AND fact_key='PUBLIC_PROFILE' AND status='APPROVED'").param("id",listing).update();
        jdbc.sql("INSERT INTO catalog.listing_fact(listing_id,fact_key,value,source_document_id,verified_at,status) VALUES(:id,'PUBLIC_PROFILE',CAST(:data AS jsonb),:source,now(),'APPROVED')")
            .param("id",listing).param("data",snapshot).param("source",source).update();
        jdbc.sql("UPDATE catalog.listing SET name=:name,search_text=:text,status='PUBLISHED',verified_at=now(),updated_at=now(),lock_version=lock_version+1 WHERE id=:id")
            .param("name",data.organizationName()).param("text",String.join(" ",data.organizationName(),data.branchName(),data.region(),data.address())).param("id",listing).update();
        jdbc.sql("""
            INSERT INTO ops.catalog_publication(draft_id,listing_id,source_document_id,approved_data,draft_version,reviewed_by,reviewed_on)
            VALUES(:draft,:listing,:source,CAST(:data AS jsonb),:draftVersion,:actor,:reviewed)
            ON CONFLICT(draft_id) DO UPDATE SET source_document_id=excluded.source_document_id,
            approved_data=excluded.approved_data,draft_version=excluded.draft_version,reviewed_by=excluded.reviewed_by,
            reviewed_on=excluded.reviewed_on,version=ops.catalog_publication.version+1,updated_at=now(),published_at=now()
            """).param("draft",draft).param("listing",listing).param("source",source).param("data",snapshot).param("draftVersion",draftVersion).param("actor",actor).param("reviewed",reviewedOn).update();
        writeDetails(listing,data.details());
        audit(actor,"CATALOG_PUBLISH",draft);
        return get(draft).orElseThrow();
    }
    @Transactional public Entry withdraw(UUID actor,UUID draft,long version){
        lockDraft(draft);
        var current=get(draft).orElseThrow(CatalogIntakeService::missing);
        if(current.version()!=version||!current.status().equals("PUBLISHED"))throw CatalogIntakeService.stale();
        jdbc.sql("UPDATE catalog.listing SET status='SUSPENDED',lock_version=lock_version+1,updated_at=now() WHERE id=:id").param("id",current.listingId()).update();
        jdbc.sql("UPDATE ops.catalog_publication SET version=version+1,updated_at=now() WHERE draft_id=:id").param("id",draft).update();
        audit(actor,"CATALOG_WITHDRAW",draft);
        return get(draft).orElseThrow();
    }
    private void writeDetails(UUID listing,CatalogDetails details){
        var p=details.parking();
        jdbc.sql("""
            INSERT INTO catalog.listing_detail(listing_id,description,parking_spaces,parking_free_minutes,parking_fee_description,valet_available,parking_access_description,shuttle_description)
            VALUES(:id,:description,:spaces,:minutes,:fee,:valet,:access,:shuttle)
            ON CONFLICT(listing_id) DO UPDATE SET description=excluded.description,parking_spaces=excluded.parking_spaces,
            parking_free_minutes=excluded.parking_free_minutes,parking_fee_description=excluded.parking_fee_description,
            valet_available=excluded.valet_available,parking_access_description=excluded.parking_access_description,shuttle_description=excluded.shuttle_description
            """).param("id",listing).param("description",details.description()).param("spaces",p.spaces()).param("minutes",p.freeMinutes())
            .param("fee",p.feeDescription()).param("valet",p.valetAvailable()).param("access",p.accessDescription()).param("shuttle",p.shuttleDescription()).update();
        jdbc.sql("UPDATE partner.branch SET parking_spaces=:spaces WHERE id=(SELECT branch_id FROM catalog.listing WHERE id=:id)").param("spaces",p.spaces()).param("id",listing).update();
        jdbc.sql("DELETE FROM catalog.reference_photo WHERE listing_id=:id").param("id",listing).update();
        for(int i=0;i<details.photos().size();i++){
            var photo=details.photos().get(i);
            jdbc.sql("INSERT INTO catalog.reference_photo VALUES(:id,:position,:url,:caption,:credit,:source,:rights,:confirmed)")
                .param("id",listing).param("position",i).param("url",photo.url()).param("caption",photo.caption()).param("credit",photo.credit())
                .param("source",photo.sourceUrl()).param("rights",photo.rights().name()).param("confirmed",photo.rightsConfirmed()).update();
        }
        jdbc.sql("DELETE FROM catalog.reference_quote WHERE listing_id=:id").param("id",listing).update();
        for(int i=0;i<details.quotes().size();i++){
            var q=details.quotes().get(i);
            jdbc.sql("""
                INSERT INTO catalog.reference_quote(listing_id,position,title,service_date,start_time,guests,minimum_guests,amount,tax_status,included,excluded,conditions,source_url,checked_on,valid_until)
                VALUES(:id,:position,:title,:date,:time,:guests,:minimum,:amount,:tax,:included,:excluded,:conditions,:source,:checked,:until)
                """).param("id",listing).param("position",i).param("title",q.title()).param("date",q.serviceDate()).param("time",q.startTime())
                .param("guests",q.guests()).param("minimum",q.minimumGuests()).param("amount",q.amount()).param("tax",q.taxStatus().name())
                .param("included",q.included()).param("excluded",q.excluded()).param("conditions",q.conditions()).param("source",q.sourceUrl()).param("checked",q.checkedOn()).param("until",q.validUntil()).update();
        }
    }
    private void audit(UUID actor,String action,UUID draft){
        jdbc.sql("INSERT INTO ops.audit_event(actor_user_id,action,target_type,target_id,redacted_changes) VALUES(:actor,:action,'CATALOG_DRAFT',:id,'{}')")
            .param("actor",actor).param("action",action).param("id",draft).update();
    }
}
