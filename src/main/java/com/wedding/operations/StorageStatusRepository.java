package com.wedding.operations;
import java.time.Instant;
import java.util.List;
public interface StorageStatusRepository {
    record Count(String name,long rows,String purpose) {}
    record Report(boolean persistent,String database,String account,String migration,boolean schemaWriteAllowed,boolean auditRewriteAllowed,List<Count> counts,Instant checkedAt) {}
    Report inspect();
}
