package com.wedding.operations;

import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("postgres")
public class PostgresCatalogDraftRepository implements CatalogDraftRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public PostgresCatalogDraftRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    private final RowMapper<Draft> mapper=(rs,n)->new Draft(rs.getObject("id",UUID.class),json.readValue(rs.getString("data"),CatalogData.class),rs.getString("status"),rs.getLong("version"),rs.getTimestamp("updated_at").toInstant());
    public Page list(int page,String status) {
        var rows=jdbc.sql("SELECT * FROM ops.catalog_draft WHERE (:status='' OR status=:status) ORDER BY updated_at DESC,id LIMIT 20 OFFSET :offset")
            .param("status",status).param("offset",page*20).query(mapper).list();
        long total=jdbc.sql("SELECT count(*) FROM ops.catalog_draft WHERE (:status='' OR status=:status)").param("status",status).query(Long.class).single();return new Page(rows,total,page);
    }
    public boolean conflicts(CatalogData data,UUID except) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.catalog_draft WHERE (external_key=:key OR identity_key=:identity) AND id<>:except)")
            .param("key",data.externalKey()).param("identity",data.identityKey()).param("except",except==null?new UUID(0,0):except).query(Boolean.class).single();
    }
    @Transactional public Draft create(UUID actor,CatalogData data){return insert(actor,data,"CATALOG_CREATE");}
    private Draft insert(UUID actor,CatalogData data,String action) {
        UUID id=UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO ops.catalog_draft(id,external_key,identity_key,data,status,created_by) VALUES(:id,:key,:identity,CAST(:data AS jsonb),'DRAFT',:actor)")
                .param("id",id).param("key",data.externalKey()).param("identity",data.identityKey()).param("data",json.writeValueAsString(data)).param("actor",actor).update();
        }catch(DuplicateKeyException ex){throw CatalogIntakeService.conflict();}
        audit(actor,action,id);return get(id);
    }
    private Draft get(UUID id){return jdbc.sql("SELECT * FROM ops.catalog_draft WHERE id=:id").param("id",id).query(mapper).optional().orElseThrow(CatalogIntakeService::missing);}
    @Transactional public Draft update(UUID actor,UUID id,long version,CatalogData data) {
        try {
            int rows=jdbc.sql("UPDATE ops.catalog_draft SET external_key=:key,identity_key=:identity,data=CAST(:data AS jsonb),version=version+1,updated_at=now() WHERE id=:id AND version=:version AND status='DRAFT'")
                .param("key",data.externalKey()).param("identity",data.identityKey()).param("data",json.writeValueAsString(data)).param("id",id).param("version",version).update();
            if(rows!=1)throw CatalogIntakeService.stale();
        }catch(DuplicateKeyException ex){throw CatalogIntakeService.conflict();}
        audit(actor,"CATALOG_UPDATE",id);return get(id);
    }
    @Transactional public Draft archive(UUID actor,UUID id,long version) {
        int rows=jdbc.sql("UPDATE ops.catalog_draft SET status='ARCHIVED',version=version+1,updated_at=now() WHERE id=:id AND version=:version AND status='DRAFT'").param("id",id).param("version",version).update();
        if(rows!=1)throw CatalogIntakeService.stale();audit(actor,"CATALOG_ARCHIVE",id);return get(id);
    }
    @Transactional public Ticket preview(UUID actor,String hash,List<CatalogData> rows) {
        // Serialize preview creation per actor, including across application instances.
        jdbc.sql("SELECT id FROM iam.user_account WHERE id=:id FOR UPDATE").param("id",actor).query(UUID.class).single();
        jdbc.sql("DELETE FROM ops.catalog_import WHERE expires_at<now() AND committed_at IS NULL").update();
        long pending=jdbc.sql("SELECT count(*) FROM ops.catalog_import WHERE owner_id=:actor AND committed_at IS NULL").param("actor",actor).query(Long.class).single();
        if(pending>=20)throw CatalogIntakeService.limited();
        UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(1800);
        jdbc.sql("INSERT INTO ops.catalog_import(id,owner_id,file_hash,rows_json,expires_at) VALUES(:id,:actor,:hash,CAST(:rows AS jsonb),:expiry)")
            .param("id",id).param("actor",actor).param("hash",hash).param("rows",json.writeValueAsString(rows)).param("expiry",java.sql.Timestamp.from(expires)).update();return new Ticket(id,expires);
    }
    private record Batch(String rows,Instant expires,String committed){}
    @Transactional public List<UUID> commit(UUID actor,UUID ticket) {
        var batch=jdbc.sql("SELECT rows_json,expires_at,result_ids FROM ops.catalog_import WHERE id=:id AND owner_id=:actor FOR UPDATE")
            .param("id",ticket).param("actor",actor).query((rs,n)->new Batch(rs.getString("rows_json"),rs.getTimestamp("expires_at").toInstant(),rs.getString("result_ids"))).optional().orElseThrow(CatalogIntakeService::missing);
        if(batch.committed()!=null)return Arrays.asList(json.readValue(batch.committed(),UUID[].class));
        if(batch.expires().isBefore(Instant.now()))throw CatalogIntakeService.expired();
        var ids=Arrays.stream(json.readValue(batch.rows(),CatalogData[].class)).map(data->insert(actor,data,"CATALOG_IMPORT").id()).toList();
        jdbc.sql("UPDATE ops.catalog_import SET committed_at=now(),result_ids=CAST(:ids AS jsonb),rows_json='[]'::jsonb WHERE id=:id")
            .param("ids",json.writeValueAsString(ids)).param("id",ticket).update();return ids;
    }
    private void audit(UUID actor,String action,UUID target) {
        jdbc.sql("INSERT INTO ops.audit_event(actor_user_id,action,target_type,target_id,redacted_changes) VALUES(:actor,:action,'CATALOG_DRAFT',:target,'{}')")
            .param("actor",actor).param("action",action).param("target",target).update();
    }
    public List<Audit> audits() {
        return jdbc.sql("SELECT id,actor_user_id,action,target_id,occurred_at FROM ops.audit_event WHERE target_type='CATALOG_DRAFT' ORDER BY occurred_at DESC,id LIMIT 50")
            .query((rs,n)->new Audit(rs.getObject("id",UUID.class),rs.getObject("actor_user_id",UUID.class),rs.getString("action"),rs.getObject("target_id",UUID.class),rs.getTimestamp("occurred_at").toInstant())).list();
    }
}
