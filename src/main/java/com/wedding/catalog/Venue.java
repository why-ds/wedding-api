package com.wedding.catalog;

import java.math.BigDecimal;
import java.util.List;

/** Read model for the first, explicitly synthetic venue comparison dataset. */
public record Venue(String id, String name, String hall, String region, String address,
                    String style, int capacity, int guarantee, BigDecimal meal,
                    BigDecimal rental, BigDecimal flowers, BigDecimal weekendExtra,
                    BigDecimal eveningDiscount, BigDecimal beveragePerGuest,
                    boolean unknownFlowers, List<String> features, String description) {}
