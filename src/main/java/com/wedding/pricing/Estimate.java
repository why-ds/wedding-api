package com.wedding.pricing;

import com.wedding.catalog.Venue;
import java.util.List;

public record Estimate(VenueSummary venue, String state, String totalMin, String totalMax,
    String knownSubtotal, int billedGuests, List<Line> lines, List<String> unknownItems,
    String availabilityState, String securityState, String comparisonContext, boolean demo) {
    public record Line(String label, String quantity, String amount, String explanation) {}
    public record VenueSummary(String id, String name, String hall, String region, String address,
        String style, int capacity, int guarantee, String meal, List<String> features, String description) {
        public static VenueSummary from(Venue v) {
            return new VenueSummary(v.id(), v.name(), v.hall(), v.region(), v.address(), v.style(),
                v.capacity(), v.guarantee(), v.meal().toPlainString(), v.features(), v.description());
        }
    }
}
