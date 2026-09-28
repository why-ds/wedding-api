package com.wedding.search;

import com.wedding.catalog.VenueRepository;
import com.wedding.pricing.*;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private final VenueRepository venues;
    private final PricingEngine engine = new PricingEngine();
    public SearchService(VenueRepository venues) { this.venues = venues; }
    public List<Estimate> search(SearchRequest r) {
        Comparator<Estimate> order = Comparator.comparingInt(e -> switch(e.state()) {
            case "COMPLETE_FIXED", "COMPLETE_RANGE" -> 0; case "PARTIAL" -> 1; default -> 2;
        });
        if ("meal".equals(r.sort())) order = order.thenComparing(e -> new BigDecimal(e.venue().meal()));
        else order = order.thenComparing(e -> e.totalMax() == null ? BigDecimal.ZERO : new BigDecimal(e.totalMax()));
        var candidates=venues.candidates(clean(r.region()),clean(r.style()),clean(r.query()));
        if(candidates.size()>500)throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"검색 대상이 많습니다. 지역·스타일·업체명을 추가해 주세요.");
        return candidates.stream()
            .map(v -> engine.calculate(v, r))
            .filter(e -> r.budget() == null || (e.totalMax() != null && new BigDecimal(e.totalMax()).compareTo(BigDecimal.valueOf(r.budget())) <= 0))
            .sorted(order.thenComparing(e -> e.venue().id())).toList();
    }
    private String clean(String s) { return s==null?"":s.strip(); }
}
