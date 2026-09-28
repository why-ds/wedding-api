package com.wedding.catalog;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class PostgresVenueRepository implements VenueRepository {
    private final JdbcClient jdbc;
    public PostgresVenueRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public List<Venue> findAll() {
        return candidates("","","");
    }
    @Override public boolean existsPublished(java.util.UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM search.demo_venue_projection WHERE id=:id)").param("id",id).query(Boolean.class).single();
    }
    @Override public List<Venue> candidates(String region,String style,String query) {
        return jdbc.sql("""
            SELECT * FROM search.demo_venue_projection
            WHERE (:region='' OR region=:region) AND (:style='' OR style=:style)
              AND (:query='' OR position(:query IN name||hall||region)>0)
            ORDER BY id LIMIT 501
            """).param("region",region).param("style",style).param("query",query).query((rs, row) ->
            new Venue(rs.getString("id"), rs.getString("name"), rs.getString("hall"), rs.getString("region"),
                rs.getString("address"), rs.getString("style"), rs.getInt("capacity"), rs.getInt("guarantee"),
                rs.getBigDecimal("meal"), rs.getBigDecimal("rental"), rs.getBigDecimal("flowers"),
                rs.getBigDecimal("weekend_extra"), rs.getBigDecimal("evening_discount"), rs.getBigDecimal("beverage_per_guest"),
                rs.getBoolean("unknown_flowers"), Arrays.asList((String[]) rs.getArray("features").getArray()), rs.getString("description"))
        ).list();
    }
}
