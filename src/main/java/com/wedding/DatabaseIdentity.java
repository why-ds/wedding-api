package com.wedding;

import java.sql.Connection;
import java.sql.SQLException;

/** Fail closed before migration or serving the deployed application. */
public final class DatabaseIdentity {
    private DatabaseIdentity() {}
    public static void require(Connection connection,String expectedUser) throws SQLException {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT current_database(),current_user,rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user")){
            if(!rows.next()||!"wedding".equals(rows.getString(1))||!expectedUser.equals(rows.getString(2))||rows.getBoolean(3))
                throw new IllegalStateException("Refusing to use an unexpected database or privileged deployment account");
        }
    }
}
