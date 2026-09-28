package com.wedding;

import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** Offline deployment command. The web process never receives migration credentials. */
public final class DatabaseMigration {
    private DatabaseMigration() {}
    public static void run() throws Exception {
        String url=required("DB_URL"),user=required("DB_USERNAME"),password=required("DB_PASSWORD");
        // This command intentionally provisions only the clearly labelled synthetic preview.
        if(!"synthetic-preview".equals(System.getenv("WEDDING_MIGRATION_MODE")))
            throw new IllegalStateException("Explicit synthetic-preview migration mode required");
        Flyway.configure().dataSource(url,user,password)
            .locations("classpath:db/migration","classpath:db/demo").load().migrate();
        try(var connection=DriverManager.getConnection(url,user,password)) { grantRuntimeAccess(connection); }
    }
    static void grantRuntimeAccess(Connection connection) throws Exception {
        try(var statement=connection.createStatement()) {
            for(String schema : new String[]{"iam","partner","catalog","pricing","planning","engagement","scheduling","ops","search"}) {
                statement.execute("GRANT USAGE ON SCHEMA "+schema+" TO wedding_app");
                statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+schema+" TO wedding_app");
                statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO wedding_app");
            }
            statement.execute("REVOKE UPDATE,DELETE ON ops.audit_event FROM wedding_app");
        }
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);
        return value;
    }
}
