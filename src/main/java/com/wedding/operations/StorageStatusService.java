package com.wedding.operations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
@Service @PreAuthorize("@adminAccess.allowed(authentication)")
public class StorageStatusService {
    private final StorageStatusRepository repository;
    public StorageStatusService(StorageStatusRepository repository){this.repository=repository;}
    public StorageStatusRepository.Report inspect(){return repository.inspect();}
}
