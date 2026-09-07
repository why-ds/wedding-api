package com.wedding;

import com.wedding.catalog.DemoVenueRepository;
import com.wedding.pricing.*;
import com.wedding.search.SearchService;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PricingEngineTest {
    private final DemoVenueRepository repo = new DemoVenueRepository();
    private final PricingEngine engine = new PricingEngine();
    private SearchRequest request(int guests, String time, Long budget, boolean drinks) {
        return new SearchRequest(LocalDate.of(2027,2,27), LocalTime.parse(time), guests, "", "", "", budget, drinks, "price");
    }
    @Test void guaranteeAndInclusiveTaxReproduceDesignExample() {
        var a = engine.calculate(repo.findAll().get(0), request(250,"12:00",null,false));
        var b = engine.calculate(repo.findAll().get(1), request(250,"12:00",null,false));
        assertEquals(300,a.billedGuests()); assertEquals("27000000",a.totalMax()); assertEquals("26500000",b.totalMax());
    }
    @Test void missingRequiredChargeNeverBecomesFreeOrBudgetEligible() {
        var e = engine.calculate(repo.findAll().get(2), request(250,"12:00",null,false));
        assertEquals("PARTIAL",e.state()); assertNull(e.totalMax()); assertFalse(e.unknownItems().isEmpty());
        assertTrue(new SearchService(repo).search(request(250,"12:00",100000000L,false)).stream().allMatch(x -> x.totalMax()!=null));
    }
    @Test void discountBoundaryAndBeveragesUseDifferentQuantities() {
        var before = engine.calculate(repo.findAll().get(0), request(250,"17:59",null,true));
        var after = engine.calculate(repo.findAll().get(0), request(250,"18:00",null,true));
        assertEquals("28250000",before.totalMax()); assertEquals("27250000",after.totalMax());
    }
    @Test void unsupportedCapacityAndDateHaveNoTotal() {
        assertEquals("UNAVAILABLE",engine.calculate(repo.findAll().get(3),request(250,"12:00",null,false)).state());
        var old = new SearchRequest(LocalDate.of(2026,12,31),LocalTime.NOON,100,"","","",null,false,"price");
        assertNull(engine.calculate(repo.findAll().get(0),old).totalMax());
    }
    @Test void fullPriceOrderingKeepsPartialAndUnavailableAfterComplete() {
        var all = new SearchService(repo).search(request(250,"12:00",null,false));
        assertEquals("메종 드 가든",all.get(0).venue().name());
        assertEquals("PARTIAL",all.get(2).state()); assertEquals("UNAVAILABLE",all.get(3).state());
        assertEquals("UNKNOWN",all.get(0).securityState());
    }
}
