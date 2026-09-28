package com.wedding.catalog;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository @Profile("postgres")
public class PostgresFavoriteListingRepository implements FavoriteListingRepository {
    private final JdbcClient jdbc;
    public PostgresFavoriteListingRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public boolean existsPublished(UUID id){
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM ops.catalog_publication p
            JOIN catalog.listing l ON l.id=p.listing_id
            JOIN partner.branch b ON b.id=l.branch_id JOIN partner.organization o ON o.id=b.organization_id
            WHERE l.id=:id AND l.status='PUBLISHED' AND b.status='ACTIVE' AND o.status='ACTIVE')
            OR EXISTS(SELECT 1 FROM search.demo_venue_projection WHERE id=:id)
            """).param("id",id).query(Boolean.class).single();
    }
}
