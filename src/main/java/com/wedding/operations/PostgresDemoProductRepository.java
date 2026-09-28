package com.wedding.operations;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository @Profile("postgres")
public class PostgresDemoProductRepository implements DemoProductRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public PostgresDemoProductRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<DemoProductSheet> get(UUID listingId) {
        return jdbc.sql("SELECT data FROM search.demo_product_sheet WHERE listing_id=:id")
            .param("id", listingId).query((rs, n) -> json.readValue(rs.getString("data"), DemoProductSheet.class)).optional();
    }
}
