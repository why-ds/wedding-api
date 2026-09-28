package com.wedding;

import com.wedding.catalog.VenueRepository;
import com.wedding.pricing.SearchRequest;
import com.wedding.search.SearchService;
import java.time.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchLimitTest {
    @Test void refusesOversizedCandidateSetInsteadOfReturningMisleadingPartialRanking() {
        var repository=mock(VenueRepository.class);
        when(repository.candidates("","","")).thenReturn(Collections.nCopies(501,null));
        var request=new SearchRequest(LocalDate.of(2027,2,27),LocalTime.NOON,250,"","","",null,false,"price");
        var failure=assertThrows(ResponseStatusException.class,()->new SearchService(repository).search(request));
        assertEquals(422,failure.getStatusCode().value());
    }
}
