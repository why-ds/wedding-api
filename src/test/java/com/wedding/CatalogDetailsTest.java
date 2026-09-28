package com.wedding;

import com.wedding.operations.*;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogDetailsTest {
    static CatalogDetails.ReferenceQuote quote(LocalDate date,LocalTime time,Integer guests,Integer minimum,LocalDate checked){
        return new CatalogDetails.ReferenceQuote("CI 참고 견적",date,time,guests,minimum,15000000L,CatalogDetails.TaxStatus.INCLUDED,"식대·대관료","음주류 별도","예시 조건","https://example.test/quote",checked,null);
    }
    static CatalogDetails fixture(){
        return new CatalogDetails("CI 테스트용 상세",new CatalogDetails.Parking(300,120,"초과 요금 별도",null,"지하 진입","미확인"),
            List.of(new CatalogDetails.Photo("https://example.test/photo.jpg","CI 사진","CI 제공자","https://example.test/photo",CatalogDetails.PhotoRights.PERMISSION,true)),
            List.of(quote(LocalDate.of(2027,2,27),LocalTime.NOON,250,200,LocalDate.now(ZoneId.of("Asia/Seoul")))));
    }
    @Test void unknownParkingAndLegacySnapshotsAreNotConvertedToFreeOrFalse(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var data=json.readValue("{\"externalKey\":\"legacy\",\"organizationName\":\"CI\",\"branchName\":\"CI\",\"category\":\"VENUE\",\"region\":\"CI\",\"address\":\"CI\"}",CatalogData.class);
        assertNull(data.details().parking().spaces());assertNull(data.details().parking().freeMinutes());assertNull(data.details().parking().valetAvailable());
        assertTrue(data.details().quotes().isEmpty());assertTrue(data.details().photos().isEmpty());
    }
    @Test void photoUrlsAreValidatedWithoutFetchingAndRightsAreRequired(){
        for(var url:List.of("http://example.test/a.jpg","https://user:secret@example.test/a","https://127.0.0.1/a","https://[::1]/a","data:image/svg+xml,test","javascript:alert(1)","https://example.test:8443/a","https://x.local/a")){
            var details=new CatalogDetails("",null,List.of(new CatalogDetails.Photo(url,"CI","CI","https://example.test",CatalogDetails.PhotoRights.OWNED,true)),null);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->details.validate(CatalogData.Category.VENUE,false));
        }
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var photo=new CatalogDetails.Photo("https://example.test/a.jpg","CI","CI","https://example.test",CatalogDetails.PhotoRights.PERMISSION,false);
            assertFalse(factory.getValidator().validate(new CatalogDetails("",null,List.of(photo),null)).isEmpty());
            assertFalse(factory.getValidator().validate(new CatalogDetails("",null,Collections.nCopies(13,fixture().photos().getFirst()),null)).isEmpty());
        }
    }
    @Test void quoteDateTimeCapacityAndFreshnessMustBeExplicit(){
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        for(var q:List.of(quote(today,LocalTime.NOON,null,null,today),quote(today,LocalTime.of(12,0,1),250,200,today),quote(today,LocalTime.NOON,250,200,today.plusDays(1)))){
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new CatalogDetails("",null,null,List.of(q)).validate(CatalogData.Category.VENUE,false));
        }
        var stale=new CatalogDetails("",null,null,List.of(quote(today,LocalTime.NOON,250,200,today.minusDays(91))));
        assertDoesNotThrow(()->stale.validate(CatalogData.Category.VENUE,false));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->stale.validate(CatalogData.Category.VENUE,true));
        assertDoesNotThrow(()->fixture().validate(CatalogData.Category.VENUE,true));
    }
}
