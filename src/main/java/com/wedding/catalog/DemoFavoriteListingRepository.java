package com.wedding.catalog;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
@Repository @Profile("demo")
public class DemoFavoriteListingRepository implements FavoriteListingRepository {
    private final VenueRepository venues;
    public DemoFavoriteListingRepository(VenueRepository venues){this.venues=venues;}
    public boolean existsPublished(UUID id){return venues.existsPublished(id);}
}
