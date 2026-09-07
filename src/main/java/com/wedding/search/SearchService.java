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
        return venues.findAll().stream()
            .filter(v -> empty(r.region()) || v.region().equals(r.region()))
            .filter(v -> empty(r.style()) || v.style().equals(r.style()))
            .filter(v -> empty(r.query()) || (v.name()+v.hall()+v.region()).contains(r.query().strip()))
            .map(v -> engine.calculate(v, r))
            .filter(e -> r.budget() == null || (e.totalMax() != null && new BigDecimal(e.totalMax()).compareTo(BigDecimal.valueOf(r.budget())) <= 0))
            .sorted(order.thenComparing(e -> e.venue().id())).toList();
    }
    private boolean empty(String s) { return s == null || s.isBlank(); }
}
