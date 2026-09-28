package com.wedding.operations;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DemoCatalogService {
    private final DemoCatalogRepository repository;
    public DemoCatalogService(DemoCatalogRepository repository){this.repository=repository;}
    public DemoCatalogRepository.Page list(int page,String category,String query){
        if(page<0||page>5000||query.length()>100||(!category.isEmpty()&&Arrays.stream(CatalogData.Category.values()).noneMatch(c->c.name().equals(category))))throw CatalogIntakeService.bad("검색 조건을 확인해 주세요.");
        return repository.list(page,category,query.strip());
    }
    public DemoCatalogRepository.Entry get(UUID id){return repository.get(id).orElseThrow(CatalogIntakeService::missing);}
}
