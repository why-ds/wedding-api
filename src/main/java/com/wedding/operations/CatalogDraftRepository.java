package com.wedding.operations;

import java.time.Instant;
import java.util.*;

public interface CatalogDraftRepository {
    record Draft(UUID id,CatalogData data,String status,long version,Instant updatedAt) {}
    record Page(List<Draft> items,long total,int page) {}
    record Audit(UUID id,UUID actorId,String action,UUID targetId,Instant occurredAt) {}
    record Ticket(UUID id,Instant expiresAt) {}
    Page list(int page,String status);
    boolean conflicts(CatalogData data,UUID except);
    Draft create(UUID actor,CatalogData data);
    Draft update(UUID actor,UUID id,long version,CatalogData data);
    Draft archive(UUID actor,UUID id,long version);
    Ticket preview(UUID actor,String hash,List<CatalogData> rows);
    List<UUID> commit(UUID actor,UUID ticket);
    List<Audit> audits();
}
