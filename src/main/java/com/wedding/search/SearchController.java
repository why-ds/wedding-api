package com.wedding.search;

import com.wedding.pricing.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SearchController {
    private final SearchService service;
    public SearchController(SearchService service) { this.service = service; }
    @GetMapping("/categories") public List<Map<String,Object>> categories() {
        return List.of(category("VENUE", "예식장", true), category("STUDIO", "스튜디오", false),
            category("DRESS", "드레스", false), category("MAKEUP", "메이크업", false),
            category("JEWELRY", "예물", false), category("HANBOK", "한복", false), category("SUIT", "예복", false));
    }
    private Map<String,Object> category(String code, String name, boolean available) { return Map.of("code",code,"name",name,"available",available); }
    @PostMapping("/searches") public Map<String,Object> search(@Valid @RequestBody SearchRequest request) {
        var results = service.search(request);
        return Map.of("results", results, "count", results.size(), "demo", true,
            "notice", "업체·가격·시설은 가상 데이터입니다. 성인 인원, 2027년 예식 기준이며 실제 예약 가능 여부와 보증금은 문의가 필요합니다.");
    }
}
