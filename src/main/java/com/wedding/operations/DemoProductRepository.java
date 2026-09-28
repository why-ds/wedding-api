package com.wedding.operations;

import java.util.Optional;
import java.util.UUID;

public interface DemoProductRepository {
    Optional<DemoProductSheet> get(UUID listingId);
}
