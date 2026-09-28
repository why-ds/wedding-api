package com.wedding.operations;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Explicit local demo profile without PostgreSQL; never a database-failure fallback. */
@Repository @Profile("demo")
public class EmptyDemoCatalogRepository implements DemoCatalogRepository {
    public Page list(int page,String category,String query){return new Page(List.of(),0,page,false);}
    public Optional<Entry> get(UUID id){return Optional.empty();}
}
