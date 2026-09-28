package com.wedding.operations;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/demo/directory")
public class DemoCatalogController {
    private final DemoCatalogService service;
    private final DemoProductService products;
    public DemoCatalogController(DemoCatalogService service,DemoProductService products){this.service=service;this.products=products;}
    private CatalogPublicationController.PublicEntry entry(DemoCatalogRepository.Entry e){var d=e.data();return new CatalogPublicationController.PublicEntry(e.id(),d.organizationName(),d.branchName(),d.category().name(),d.region(),d.address(),d.publicPhone(),d.sourceUrl(),java.time.LocalDate.of(2026,9,28));}
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String query){
        var result=service.list(page,category,query);
        return Map.of("items",result.items().stream().map(this::entry).toList(),"total",result.total(),"page",page,"persistent",result.persistent(),"demo",true);
    }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){
        var e=service.get(id);
        var result=new LinkedHashMap<String,Object>();
        result.put("listing",entry(e));result.put("details",e.data().details());result.put("timezone","Asia/Seoul");result.put("demo",true);
        products.find(id).ifPresent(sheet->result.put("productSheet",sheet));
        return result;
    }
    @GetMapping("/{id}/product-estimate") public DemoProductService.Estimate estimate(@PathVariable UUID id,
            @RequestParam String productId,@RequestParam(defaultValue="") List<String> option,
            @RequestParam(defaultValue="1") int hours,@RequestParam(defaultValue="1") int extensionOperators,
            @RequestParam(defaultValue="UNKNOWN") DemoProductService.TravelChoice travel){
        return products.estimate(id,productId,option,hours,extensionOperators,travel);
    }
}
