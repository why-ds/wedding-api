package com.wedding.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class CatalogPublicationController {
    private final CatalogPublicationService service;
    private final com.wedding.identity.MemberService members;
    public CatalogPublicationController(CatalogPublicationService service,com.wedding.identity.MemberService members){this.service=service;this.members=members;}
    public record Publish(@NotNull @Min(0) Long version,@Min(0) Long publicationVersion,@NotNull LocalDate reviewedOn,@AssertTrue boolean factsConfirmed){}
    public record Version(@NotNull @Min(0) Long version){}
    // Explicit public projection excludes internal draft keys, reviewer IDs and audit data.
    public record PublicEntry(UUID id,String organizationName,String branchName,String category,String region,String address,String publicPhone,String sourceUrl,LocalDate reviewedOn){}
    @GetMapping("/directory") public Map<String,Object> browse(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String query){
        var result=service.browse(page,category,query);
        return response(result);
    }
    @GetMapping("/me/directory") public Map<String,Object> saved(Principal principal,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String query){
        UUID actor=UUID.fromString(principal.getName());members.get(actor);
        return response(service.saved(actor,page,category,query));
    }
    private Map<String,Object> response(PublicationRepository.Page result){
        var items=result.items().stream().map(e->new PublicEntry(e.listingId(),e.data().organizationName(),e.data().branchName(),e.data().category().name(),e.data().region(),e.data().address(),e.data().publicPhone(),e.data().sourceUrl(),e.reviewedOn())).toList();
        return Map.of("items",items,"total",result.total(),"page",result.page(),"persistent",result.persistent());
    }
    @GetMapping("/admin/publications") public PublicationRepository.Page list(@RequestParam(defaultValue="0") int page){return service.administration(page);}
    @GetMapping("/admin/catalog/{id}/publication") public Map<String,Object> state(@PathVariable UUID id){return Collections.singletonMap("publication",service.state(id).orElse(null));}
    @PostMapping("/admin/catalog/{id}/publish") public PublicationRepository.Entry publish(Principal principal,@PathVariable UUID id,@Valid @RequestBody Publish request){return service.publish(UUID.fromString(principal.getName()),id,request.version(),request.publicationVersion(),request.reviewedOn(),request.factsConfirmed());}
    @PostMapping("/admin/catalog/{id}/withdraw") public PublicationRepository.Entry withdraw(Principal principal,@PathVariable UUID id,@Valid @RequestBody Version request){return service.withdraw(UUID.fromString(principal.getName()),id,request.version());}
}
