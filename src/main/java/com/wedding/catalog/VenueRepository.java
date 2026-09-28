package com.wedding.catalog;

import java.util.List;

public interface VenueRepository {
    List<Venue> findAll();
    default List<Venue> candidates(String region,String style,String query) {
        return findAll().stream()
            .filter(v->region.isEmpty()||v.region().equals(region))
            .filter(v->style.isEmpty()||v.style().equals(style))
            .filter(v->query.isEmpty()||(v.name()+v.hall()+v.region()).contains(query))
            .limit(501).toList();
    }
    default boolean existsPublished(java.util.UUID id) {
        return findAll().stream().anyMatch(v->v.id().equals(id.toString()));
    }
}
