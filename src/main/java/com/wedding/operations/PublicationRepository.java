package com.wedding.operations;

import java.time.*;
import java.util.*;

public interface PublicationRepository {
    record Entry(UUID draftId,UUID listingId,CatalogData data,String status,long draftVersion,long version,LocalDate reviewedOn,Instant updatedAt) {}
    record Page(List<Entry> items,long total,int page,boolean persistent) {}
    Page list(int page,String category,String query,boolean publishedOnly);
    Optional<Entry> get(UUID draft);
    Entry publish(UUID actor,UUID draft,long draftVersion,Long publicationVersion,CatalogData data,LocalDate reviewedOn);
    Entry withdraw(UUID actor,UUID draft,long publicationVersion);
}
