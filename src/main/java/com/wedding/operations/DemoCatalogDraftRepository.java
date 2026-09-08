package com.wedding.operations;

import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("demo")
public class DemoCatalogDraftRepository implements CatalogDraftRepository {
    private record Batch(UUID owner,Instant expires,List<CatalogData> rows,List<UUID> committed) {}
    private final Map<UUID,Draft> drafts=new LinkedHashMap<>();
    private final Map<UUID,Batch> batches=new HashMap<>();
    private final List<Audit> audit=new ArrayList<>();
    public synchronized Page list(int page,String status) {
        var all=drafts.values().stream().filter(d->status.isEmpty()||d.status().equals(status))
            .sorted(Comparator.comparing(Draft::updatedAt).reversed().thenComparing(Draft::id)).toList();
        return new Page(all.stream().skip((long)page*20).limit(20).toList(),all.size(),page);
    }
    public synchronized boolean conflicts(CatalogData data,UUID except) {return drafts.values().stream().anyMatch(d->!d.id().equals(except)&&(d.data().externalKey().equals(data.externalKey())||d.data().identityKey().equals(data.identityKey())));}
    private Draft add(UUID actor,CatalogData data,String action) {
        var d=new Draft(UUID.randomUUID(),data,"DRAFT",0,Instant.now());drafts.put(d.id(),d);record(actor,action,d.id());return d;
    }
    public synchronized Draft create(UUID actor,CatalogData data) {if(conflicts(data,null))throw CatalogIntakeService.conflict();return add(actor,data,"CATALOG_CREATE");}
    public synchronized Draft update(UUID actor,UUID id,long version,CatalogData data) {
        var old=editable(id,version);if(conflicts(data,id))throw CatalogIntakeService.conflict();
        var d=new Draft(id,data,old.status(),old.version()+1,Instant.now());drafts.put(id,d);record(actor,"CATALOG_UPDATE",id);return d;
    }
    public synchronized Draft archive(UUID actor,UUID id,long version) {
        var old=editable(id,version);var d=new Draft(id,old.data(),"ARCHIVED",old.version()+1,Instant.now());drafts.put(id,d);record(actor,"CATALOG_ARCHIVE",id);return d;
    }
    private Draft editable(UUID id,long version) {
        var old=drafts.get(id);if(old==null)throw CatalogIntakeService.missing();
        if(old.version()!=version||!old.status().equals("DRAFT"))throw CatalogIntakeService.stale();return old;
    }
    public synchronized Ticket preview(UUID actor,String hash,List<CatalogData> rows) {
        batches.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
        if(batches.size()>=200)throw CatalogIntakeService.limited();
        UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(1800);
        batches.put(id,new Batch(actor,expires,List.copyOf(rows),null));return new Ticket(id,expires);
    }
    public synchronized List<UUID> commit(UUID actor,UUID ticket) {
        var batch=batches.get(ticket);
        if(batch==null||!batch.owner().equals(actor))throw CatalogIntakeService.missing();
        if(batch.committed()!=null)return batch.committed();
        if(batch.expires().isBefore(Instant.now()))throw CatalogIntakeService.expired();
        for(var row:batch.rows())if(conflicts(row,null))throw CatalogIntakeService.conflict();
        var ids=batch.rows().stream().map(row->add(actor,row,"CATALOG_IMPORT").id()).toList();
        batches.put(ticket,new Batch(actor,batch.expires(),List.of(),ids));return ids;
    }
    private void record(UUID actor,String action,UUID target) {audit.add(new Audit(UUID.randomUUID(),actor,action,target,Instant.now()));}
    public synchronized List<Audit> audits(){return audit.reversed().stream().limit(50).toList();}
}
