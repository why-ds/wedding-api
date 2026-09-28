package com.wedding.operations;

import java.util.*;

/** Isolated fixtures: never treated as approved partners, quotes or favorites. */
public interface DemoCatalogRepository {
    record Entry(UUID id,CatalogData data){}
    record Page(List<Entry> items,long total,int page,boolean persistent){}
    Page list(int page,String category,String query);
    Optional<Entry> get(UUID id);
}
