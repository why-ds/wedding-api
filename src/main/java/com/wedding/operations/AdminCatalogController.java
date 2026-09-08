package com.wedding.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {
    private final CatalogIntakeService service;
    public AdminCatalogController(CatalogIntakeService service){this.service=service;}
    public record Update(@NotNull @Valid CatalogData data,@Min(0) long version){}
    public record Version(@Min(0) long version){}
    @GetMapping("/catalog") public CatalogDraftRepository.Page list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="")String status){return service.list(page,status);}
    @PostMapping("/catalog") @ResponseStatus(HttpStatus.CREATED)
    public CatalogDraftRepository.Draft create(Principal principal,@RequestBody CatalogData data){return service.create(id(principal),data);}
    @PutMapping("/catalog/{id}") public CatalogDraftRepository.Draft update(Principal principal,@PathVariable UUID id,@Valid @RequestBody Update input){return service.update(id(principal),id,input.version(),input.data());}
    @PostMapping("/catalog/{id}/archive") public CatalogDraftRepository.Draft archive(Principal principal,@PathVariable UUID id,@Valid @RequestBody Version input){return service.archive(id(principal),id,input.version());}
    @PostMapping(value="/imports/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CatalogIntakeService.Preview preview(Principal principal,@RequestParam MultipartFile file){return service.preview(id(principal),file);}
    @PostMapping("/imports/{id}/commit") public Map<String,Object> commit(Principal principal,@PathVariable UUID id){var ids=service.commit(id(principal),id);return Map.of("ids",ids,"count",ids.size());}
    @GetMapping("/audit") public List<CatalogDraftRepository.Audit> audit(){return service.audits();}
    @GetMapping(value="/imports/template",produces="text/csv;charset=UTF-8") public ResponseEntity<String> template(){
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"catalog-template.csv\"")
            .body("\uFEFF"+String.join(",",CatalogIntakeService.HEADERS)+"\r\nexample-venue,예시 웨딩,강남점,VENUE,서울 강남,서울 강남구 예시 주소,02-0000-0000,https://example.com\r\n");
    }
    private UUID id(Principal principal){return UUID.fromString(principal.getName());}
}
