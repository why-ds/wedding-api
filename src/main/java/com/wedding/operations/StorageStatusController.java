package com.wedding.operations;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/storage")
public class StorageStatusController {
    private final StorageStatusService service;
    public StorageStatusController(StorageStatusService service){this.service=service;}
    @GetMapping public StorageStatusRepository.Report inspect(){return service.inspect();}
}
