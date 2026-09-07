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
        return jdbc.sql("SELECT * FROM search.demo_venue_projection ORDER BY id").query((rs, row) ->
            new Venue(rs.getString("id"), rs.getString("name"), rs.getString("hall"), rs.getString("region"),
                rs.getString("address"), rs.getString("style"), rs.getInt("capacity"), rs.getInt("guarantee"),
                rs.getBigDecimal("meal"), rs.getBigDecimal("rental"), rs.getBigDecimal("flowers"),
                rs.getBigDecimal("weekend_extra"), rs.getBigDecimal("evening_discount"), rs.getBigDecimal("beverage_per_guest"),
                rs.getBoolean("unknown_flowers"), Arrays.asList((String[]) rs.getArray("features").getArray()), rs.getString("description"))
        ).list();
    }
}
