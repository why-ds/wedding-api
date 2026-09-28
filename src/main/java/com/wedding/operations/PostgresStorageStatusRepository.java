package com.wedding.operations;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository @Profile("postgres")
public class PostgresStorageStatusRepository implements StorageStatusRepository {
    private final JdbcClient jdbc;
    public PostgresStorageStatusRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true) public Report inspect(){
        var database=jdbc.sql("SELECT current_database()").query(String.class).single();
        var account=jdbc.sql("SELECT current_user").query(String.class).single();
        // Runtime does not receive access to Flyway internals; identify application schema by known tables.
        var migration=jdbc.sql("SELECT CASE WHEN to_regclass('catalog.reference_quote') IS NOT NULL THEN 'V905 이상' WHEN to_regclass('catalog.capture_spec') IS NOT NULL AND to_regclass('ops.catalog_publication') IS NOT NULL THEN 'V904 이상' ELSE '기본 스키마' END").query(String.class).single();
        var ddl=jdbc.sql("SELECT bool_or(has_schema_privilege(current_user,nspname,'CREATE')) FROM pg_namespace WHERE nspname IN ('iam','partner','catalog','pricing','planning','ops')").query(Boolean.class).single();
        var rewrite=jdbc.sql("SELECT has_table_privilege(current_user,'ops.audit_event','UPDATE') OR has_table_privilege(current_user,'ops.audit_event','DELETE')").query(Boolean.class).single();
        var counts=List.of(count("iam.user_account","회원"),count("ops.catalog_draft","업체 초안"),count("ops.catalog_publication","게시 이력 · 중단 포함"),count("planning.user_favorite","회원 찜"),count("ops.catalog_import","CSV 검증·저장 기록"),count("ops.audit_event","관리자 변경 이력"),count("catalog.product_version","상품 버전 · 가상 자료 포함"),count("pricing.estimate","저장 견적 · 저장 기능 미구현"),count("catalog.listing_detail","검수된 상세 정보"),count("catalog.reference_photo","검수된 사진 주소"),count("catalog.reference_quote","조건별 참고 견적 · 자동 계산 아님"));
        return new Report(true,database,account,migration,ddl,rewrite,counts,Instant.now());
    }
    private Count count(String table,String purpose){return new Count(table,jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single(),purpose);}
}
