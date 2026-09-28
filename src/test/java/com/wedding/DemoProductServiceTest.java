package com.wedding;

import com.wedding.operations.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DemoProductServiceTest {
    static final UUID PHONE=UUID.fromString("91000000-0000-4000-8000-000000000091");
    static final UUID PHOTO=UUID.fromString("91000000-0000-4000-8000-000000000081");
    static final DemoProductService.TravelChoice UNKNOWN=DemoProductService.TravelChoice.UNKNOWN;
    final DemoProductService service=new DemoProductService(fixtures());
    static DemoProductRepository fixtures() {
        try(var input=DemoProductServiceTest.class.getResourceAsStream("/db/demo/V909__reference_product_samples.sql")) {
            String sql=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
            var matcher=Pattern.compile("VALUES \\('([^']+)', \\$fixture\\$ (.*?)\\$fixture\\$::jsonb\\);").matcher(sql);
            Map<UUID,DemoProductSheet> sheets=new HashMap<>();var json=JsonMapper.builder().build();
            while(matcher.find())sheets.put(UUID.fromString(matcher.group(1)),json.readValue(matcher.group(2),DemoProductSheet.class));
            assertEquals(2,sheets.size());return id->Optional.ofNullable(sheets.get(id));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @Test void phonePricesOptionsAndTravelStaySeparate() {
        var basic=service.estimate(PHONE,"basic",List.of("coverage-basic","representative"),1,1,UNKNOWN);
        assertEquals(480000,basic.subtotal());assertNull(basic.rangeMaximum());assertEquals(CatalogDetails.TaxStatus.INCLUDED,basic.taxStatus());
        var signature=service.estimate(PHONE,"signature",List.of("coverage-signature","representative"),1,1,DemoProductService.TravelChoice.APPLIES);
        assertEquals(550000,signature.subtotal());assertEquals(570000L,signature.rangeMinimum());assertEquals(600000L,signature.rangeMaximum());
        var high=service.estimate(PHONE,"high-end",List.of("representative"),1,1,DemoProductService.TravelChoice.NOT_APPLICABLE);
        assertEquals(580000,high.subtotal());assertEquals(580000L,high.rangeMaximum());assertFalse(high.unresolved().isEmpty());
    }
    @Test void twoPersonDesignationDoesNotAddAnotherPhotographerCharge() {
        var director=service.estimate(PHOTO,"album-80",List.of("director"),1,1,UNKNOWN);
        assertEquals(2900000,director.subtotal());assertEquals(2,director.operators());assertEquals(2,director.lines().size());
        assertEquals(CatalogDetails.TaxStatus.UNKNOWN,director.taxStatus());
        var vice=service.estimate(PHOTO,"album-60",List.of("vice-director","extra-time"),2,1,UNKNOWN);
        assertEquals(3000000,vice.subtotal()); // 1.3m + 1.1m + .3m * 2h * ONE extending photographer
        var both=service.estimate(PHOTO,"album-60",List.of("vice-director","extra-time"),2,2,UNKNOWN);
        assertEquals(3600000,both.subtotal());
        var three=service.estimate(PHOTO,"album-60",List.of("vice-director","extra-photographer","makeup-shop","extra-time"),2,3,UNKNOWN);
        assertEquals(4900000,three.subtotal());assertEquals(3,three.operators());
    }
    @Test void rejectsUnsupportedDuplicateExclusiveAndExcessiveChoices() {
        bad(()->service.estimate(PHONE,"high-end",List.of("coverage-basic"),1,1,UNKNOWN));
        bad(()->service.estimate(PHONE,"basic",List.of("representative","representative"),1,1,UNKNOWN));
        bad(()->service.estimate(PHOTO,"album-60",List.of("director","vice-director"),1,1,UNKNOWN));
        bad(()->service.estimate(PHOTO,"album-60",List.of("extra-time"),1,2,UNKNOWN));
        bad(()->service.estimate(PHOTO,"album-60",List.of("extra-time"),13,1,UNKNOWN));
        bad(()->service.estimate(PHOTO,"album-60",List.of("unknown"),1,1,UNKNOWN));
        bad(()->service.estimate(PHOTO,"unknown",List.of(),1,1,UNKNOWN));
        bad(()->service.estimate(PHOTO,"album-60",Collections.nCopies(13,"manager"),1,1,UNKNOWN));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.estimate(UUID.randomUUID(),"basic",List.of(),1,1,UNKNOWN)).getStatusCode().value());
    }
    @Test void missingTravelAndTaxAreNotInvented() {
        var result=service.estimate(PHOTO,"album-60",List.of(),1,1,DemoProductService.TravelChoice.APPLIES);
        assertNull(result.travelMaximum());assertNull(result.rangeMaximum());assertEquals(1300000,result.subtotal());
        assertTrue(result.unresolved().stream().anyMatch(s->s.contains("부가세")));
        assertEquals(3,service.find(PHONE).orElseThrow().products().size());
    }
    private void bad(org.junit.jupiter.api.function.Executable action) {assertEquals(400,assertThrows(ResponseStatusException.class,action).getStatusCode().value());}
}
