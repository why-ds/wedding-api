package com.wedding.operations;

import java.time.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class CatalogPublicationService {
    private final PublicationRepository publications;
    private final CatalogDraftRepository drafts;
    private final CatalogIntakeService intake;
    public CatalogPublicationService(PublicationRepository publications,CatalogDraftRepository drafts,CatalogIntakeService intake){this.publications=publications;this.drafts=drafts;this.intake=intake;}
    public PublicationRepository.Page browse(int page,String category,String query){return list(page,category,query,true);}
    public PublicationRepository.Entry detail(UUID listing){return publications.findPublished(listing).orElseThrow(CatalogIntakeService::missing);}
    @PreAuthorize("isAuthenticated()")
    public PublicationRepository.Page saved(UUID actor,int page,String category,String query){
        validate(page,category,query);return publications.list(page,category,query.strip(),true,actor);
    }
    @PreAuthorize("@adminAccess.allowed(authentication)")
    public PublicationRepository.Page administration(int page){return list(page,"","",false);}
    private PublicationRepository.Page list(int page,String category,String query,boolean publishedOnly){
        validate(page,category,query);
        return publications.list(page,category,query.strip(),publishedOnly);
    }
    private void validate(int page,String category,String query){
        if(page<0||page>5000||query.length()>100||(!category.isEmpty()&&Arrays.stream(CatalogData.Category.values()).noneMatch(c->c.name().equals(category))))throw CatalogIntakeService.bad("검색 조건을 확인해 주세요.");
    }
    @PreAuthorize("@adminAccess.allowed(authentication)")
    public Optional<PublicationRepository.Entry> state(UUID draft){drafts.get(draft);return publications.get(draft);}
    @PreAuthorize("@adminAccess.allowed(authentication)")
    public PublicationRepository.Entry publish(UUID actor,UUID draft,long version,Long publicationVersion,LocalDate reviewedOn,boolean confirmed){
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        if(!confirmed||reviewedOn==null||reviewedOn.isAfter(today)||reviewedOn.isBefore(today.minusDays(90)))throw CatalogIntakeService.bad("최근 90일 이내에 출처와 공개할 기본 정보를 직접 확인해 주세요.");
        var current=drafts.get(draft);
        if(current.version()!=version||!current.status().equals("DRAFT"))throw CatalogIntakeService.stale();
        var data=intake.checked(current.data());
        data.details().validate(data.category(),true);
        if(data.sourceUrl().isBlank())throw CatalogIntakeService.bad("공개 게시 전에 초안에 출처 URL을 입력해 주세요.");
        return publications.publish(actor,draft,version,publicationVersion,data,reviewedOn);
    }
    @PreAuthorize("@adminAccess.allowed(authentication)")
    public PublicationRepository.Entry withdraw(UUID actor,UUID draft,long version){return publications.withdraw(actor,draft,version);}
}
