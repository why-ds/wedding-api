package com.wedding.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Reviewed descriptive facts and source-specific quotes, not executable pricing rules. */
public record CatalogDetails(
    @Size(max=2000) String description,
    @Valid Parking parking,
    @Size(max=12) List<@NotNull @Valid Photo> photos,
    @Size(max=24) List<@NotNull @Valid ReferenceQuote> quotes
) {
    public CatalogDetails {
        description=description==null?"":description.strip();
        parking=parking==null?new Parking(null,null,"",null,"",""):parking;
        photos=photos==null?List.of():Collections.unmodifiableList(new ArrayList<>(photos));
        quotes=quotes==null?List.of():Collections.unmodifiableList(new ArrayList<>(quotes));
    }
    public static CatalogDetails empty(){return new CatalogDetails("",null,null,null);}
    public record Parking(
        @Min(0) @Max(100000) Integer spaces,
        @Min(0) @Max(10080) Integer freeMinutes,
        @Size(max=1000) String feeDescription,
        Boolean valetAvailable,
        @Size(max=1000) String accessDescription,
        @Size(max=1000) String shuttleDescription
    ) {}
    public enum PhotoRights { OWNED, PERMISSION, LICENSED }
    public record Photo(
        @NotBlank @Size(max=1000) String url,
        @NotBlank @Size(max=200) String caption,
        @NotBlank @Size(max=200) String credit,
        @NotBlank @Size(max=1000) String sourceUrl,
        @NotNull PhotoRights rights,
        @AssertTrue boolean rightsConfirmed
    ) {}
    public enum TaxStatus { INCLUDED, EXCLUDED, UNKNOWN }
    public record ReferenceQuote(
        @NotBlank @Size(max=120) String title,
        @NotNull LocalDate serviceDate,
        @NotNull LocalTime startTime,
        @Min(1) @Max(10000) Integer guests,
        @Min(1) @Max(10000) Integer minimumGuests,
        @NotNull @Min(0) @Max(100000000000L) Long amount,
        @NotNull TaxStatus taxStatus,
        @NotBlank @Size(max=1200) String included,
        @NotBlank @Size(max=1200) String excluded,
        @Size(max=1200) String conditions,
        @NotBlank @Size(max=1000) String sourceUrl,
        @NotNull LocalDate checkedOn,
        LocalDate validUntil
    ) {}
    public void validate(CatalogData.Category category,boolean publishing) {
        for(var photo:photos){
            if(photo==null)throw CatalogIntakeService.bad("사진 정보를 확인해 주세요.");
            if(!safeHttps(photo.url())||!safeHttps(photo.sourceUrl()))throw CatalogIntakeService.bad("사진과 사진 출처는 인증정보 없는 공개 HTTPS 주소를 입력해 주세요.");
        }
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        for(var q:quotes){
            if(q==null||q.serviceDate()==null||q.startTime()==null||q.checkedOn()==null)throw CatalogIntakeService.bad("견적의 적용 날짜·시각·확인일이 필요합니다.");
            if(q.startTime().getSecond()!=0||q.startTime().getNano()!=0||q.serviceDate().getYear()<2020||q.serviceDate().getYear()>2100)
                throw CatalogIntakeService.bad("적용 날짜는 2020~2100년, 시각은 분 단위로 입력해 주세요.");
            if(category==CatalogData.Category.VENUE&&(q.guests()==null||q.minimumGuests()==null))throw CatalogIntakeService.bad("예식장 참고 견적에는 예상 인원과 최소 보증 인원이 필요합니다.");
            if(q.minimumGuests()!=null&&q.guests()==null)throw CatalogIntakeService.bad("최소 보증 인원을 입력할 때 예상 인원도 입력해 주세요.");
            if(!safeHttps(q.sourceUrl()))throw CatalogIntakeService.bad("견적 출처는 인증정보 없는 공개 HTTPS 주소를 입력해 주세요.");
            if(q.checkedOn().isAfter(today)||q.checkedOn().getYear()<2020||(q.validUntil()!=null&&q.validUntil().isBefore(q.checkedOn())))throw CatalogIntakeService.bad("견적 확인일과 유효 기한을 확인해 주세요.");
            if(publishing&&q.checkedOn().isBefore(today.minusDays(90)))throw CatalogIntakeService.bad("게시할 참고 견적은 최근 90일 이내에 출처를 다시 확인해 주세요.");
        }
    }
    // Validation only: the server never resolves or fetches these external URLs.
    private static boolean safeHttps(String value){
        if(value==null)return false;
        try {
            var uri=URI.create(value);var host=uri.getHost();
            if(!"https".equals(uri.getScheme())||host==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
            host=host.toLowerCase(Locale.ROOT);
            return host.matches("(?=.{1,253}$)[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}")&&!host.endsWith(".localhost")&&!host.endsWith(".local")&&!host.endsWith(".internal");
        }catch(IllegalArgumentException ex){return false;}
    }
}
