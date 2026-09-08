package com.wedding.catalog;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("demo")
public class DemoVenueRepository implements VenueRepository {
    private static BigDecimal won(long value) { return BigDecimal.valueOf(value); }
    @Override public List<Venue> findAll() {
        return List.of(
            new Venue("10000000-0000-4000-8000-000000000001", "오브 그랜드", "그랜드홀", "강남", "서울 강남구 · 가상 주소", "호텔", 500, 300,
                won(70000), won(4000000), won(2000000), won(0), won(1000000), won(5000), false,
                List.of("단독 예식", "높은 층고", "주차 300대"), "여유로운 공간과 차분한 분위기의 호텔형 홀입니다. 모든 정보는 개발용 가상 데이터입니다."),
            new Venue("10000000-0000-4000-8000-000000000002", "메종 드 가든", "가든홀", "서초", "서울 서초구 · 가상 주소", "가든", 350, 250,
                won(80000), won(5000000), won(1500000), won(0), won(1500000), won(6000), false,
                List.of("자연 채광", "실내 가든", "분리 예식"), "초록의 정원과 따뜻한 자연광을 담은 실내 가든형 홀입니다. 개발용 가상 업체입니다."),
            new Venue("10000000-0000-4000-8000-000000000003", "아틀리에 서울", "채플홀", "송파", "서울 송파구 · 가상 주소", "채플", 300, 200,
                won(65000), won(3500000), won(0), won(1000000), won(500000), won(4500), true,
                List.of("채플 예식", "프라이빗 로비", "주ㄴ차 200대"), "간결한 채플 공간에서 두 사람에게 집중하는 예식입니다. 장식 비용은 확인이 필요합니다."),
            new Venue("10000000-0000-4000-8000-000000000004", "더 리버 하우스", "리버홀", "영등포", "서울 영등포구 · 가상 주소", "하우스", 180, 100,
                won(95000), won(3000000), won(1000000), won(1500000), won(500000), won(7000), false,
                List.of("소규모 예식", "테라스", "단독 대관"), "가까운 사람들과 함께하는 소규모 하우스 예식 공간입니다. 개발용 가상 업체입니다.")
        );
    }
}
