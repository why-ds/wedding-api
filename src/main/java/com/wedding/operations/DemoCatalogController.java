package com.wedding.operations;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/demo/directory")
public class DemoCatalogController {
    private final DemoCatalogService service;
    public DemoCatalogController(DemoCatalogService service){this.service=service;}
    private CatalogPublicationController.PublicEntry entry(DemoCatalogRepository.Entry e){var d=e.data();return new CatalogPublicationController.PublicEntry(e.id(),d.organizationName(),d.branchName(),d.category().name(),d.region(),d.address(),d.publicPhone(),d.sourceUrl(),java.time.LocalDate.of(2026,9,28));}
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String query){
        var result=service.list(page,category,query);
        return Map.of("items",result.items().stream().map(this::entry).toList(),"total",result.total(),"page",page,"persistent",result.persistent(),"demo",true);
    }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){var e=service.get(id);return Map.of("listing",entry(e),"details",e.data().details(),"timezone","Asia/Seoul","demo",true);}
}
