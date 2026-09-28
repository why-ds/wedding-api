package com.wedding.operations;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
@Repository @Profile("demo")
public class DemoStorageStatusRepository implements StorageStatusRepository {
    public Report inspect(){return new Report(false,"미연결","메모리 체험","없음",false,false,List.of(),Instant.now());}
}
