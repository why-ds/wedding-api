package com.wedding.operations;

import java.util.List;

/** User-provided reference material, isolated from reviewed and published offers. */
public record DemoProductSheet(String sourceKind, String sourceDescription, String categoryNote,
        List<String> caveats, Travel travel, List<Product> products, List<Option> options) {
    public record Fact(String label, String value) {}
    public record Product(String id, String name, long amount, Long previousAmount,
            CatalogDetails.TaxStatus taxStatus, int operators, List<Fact> facts) {}
    public enum Unit { ONCE, PER_HOUR_PER_OPERATOR }
    public record Option(String id, String name, long amount, Unit unit, String group,
            List<String> productIds, Integer operatorsOverride, int additionalOperators, String description) {}
    public record Travel(Long minimum, Long maximum, String description) {}
}
