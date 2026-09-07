package com.wedding.pricing;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

public record SearchRequest(@NotNull LocalDate date, @NotNull LocalTime time,
    @Min(1) @Max(2000) int guests, @Size(max=30) String region,
    @Size(max=30) String style, @Size(max=100) String query,
    @Min(0) @Max(1000000000) Long budget, boolean beverages,
    @Pattern(regexp="recommended|price|meal") String sort) {}
