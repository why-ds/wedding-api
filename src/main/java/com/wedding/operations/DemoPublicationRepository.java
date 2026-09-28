package com.wedding.operations;

import java.time.LocalDate;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Publishing needs a durable database. Demo intake must never become real public data. */
@Repository @Profile("demo")
public class DemoPublicationRepository implements PublicationRepository {
    public Page list(int page,String category,String query,boolean publishedOnly){return new Page(List.of(),0,page,false);}
    public Optional<Entry> get(UUID draft){return Optional.empty();}
    public Entry publish(UUID actor,UUID draft,long version,Long publicationVersion,CatalogData data,LocalDate reviewedOn){throw unavailable();}
    public Entry withdraw(UUID actor,UUID draft,long version){throw unavailable();}
    private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.CONFLICT,"공개 게시에는 PostgreSQL 연결이 필요합니다. 현재는 체험 모드입니다.");}
}
