package com.wedding.operations;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository @Profile("postgres")
public class PostgresDemoCatalogRepository implements DemoCatalogRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    private final RowMapper<Entry> mapper=(rs,n)->new Entry(rs.getObject("id",UUID.class),json.readValue(rs.getString("data"),CatalogData.class));
    public PostgresDemoCatalogRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public Page list(int page,String category,String query){
        String where=" WHERE (:category='' OR category_code=:category) AND (:query='' OR position(lower(:query) IN lower(concat(data->>'organizationName',' ',data->>'region')))>0)";
        var rows=jdbc.sql("SELECT id,data FROM search.demo_catalog_entry"+where+" ORDER BY id LIMIT 20 OFFSET :offset").param("category",category).param("query",query).param("offset",page*20).query(mapper).list();
        long total=jdbc.sql("SELECT count(*) FROM search.demo_catalog_entry"+where).param("category",category).param("query",query).query(Long.class).single();
        return new Page(rows,total,page,true);
    }
    public Optional<Entry> get(UUID id){return jdbc.sql("SELECT id,data FROM search.demo_catalog_entry WHERE id=:id").param("id",id).query(mapper).optional();}
}
