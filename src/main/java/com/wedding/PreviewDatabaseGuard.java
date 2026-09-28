package com.wedding;

import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("preview")
public class PreviewDatabaseGuard {
    public PreviewDatabaseGuard(DataSource dataSource) throws Exception {
        try(var connection=dataSource.getConnection()) { DatabaseIdentity.require(connection,"wedding_app"); }
    }
}
