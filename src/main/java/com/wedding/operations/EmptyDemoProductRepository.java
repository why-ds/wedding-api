package com.wedding.operations;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository @Profile("demo")
public class EmptyDemoProductRepository implements DemoProductRepository {
    public Optional<DemoProductSheet> get(UUID listingId) { return Optional.empty(); }
}
