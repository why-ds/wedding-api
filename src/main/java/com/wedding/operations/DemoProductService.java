package com.wedding.operations;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DemoProductService {
    public enum TravelChoice { UNKNOWN, APPLIES, NOT_APPLICABLE }
    public record Line(String name, long unitAmount, int quantity, long amount) {}
    public record Estimate(boolean demo, String productId, List<Line> lines, long subtotal,
            Long travelMinimum, Long travelMaximum, Long rangeMinimum, Long rangeMaximum,
            int operators, CatalogDetails.TaxStatus taxStatus, List<String> unresolved) {}
    private final DemoProductRepository repository;
    public DemoProductService(DemoProductRepository repository) { this.repository = repository; }
    public Optional<DemoProductSheet> find(UUID id) { return repository.get(id); }

    public Estimate estimate(UUID id, String productId, List<String> optionIds, int hours,
            int extensionOperators, TravelChoice travelChoice) {
        if (productId.length() > 60 || optionIds.size() > 12 || optionIds.stream().anyMatch(s -> s.length() > 60)
                || new HashSet<>(optionIds).size() != optionIds.size() || hours < 1 || hours > 12
                || extensionOperators < 1 || extensionOperators > 3) throw CatalogIntakeService.bad("상품·옵션·연장 시간·인원을 확인해 주세요.");
        var sheet = repository.get(id).orElseThrow(CatalogIntakeService::missing);
        var product = sheet.products().stream().filter(p -> p.id().equals(productId)).findFirst()
            .orElseThrow(() -> CatalogIntakeService.bad("등록되지 않은 상품입니다."));
        var options = optionIds.stream().map(code -> sheet.options().stream()
            .filter(o -> o.id().equals(code) && o.productIds().contains(productId)).findFirst()
            .orElseThrow(() -> CatalogIntakeService.bad("이 상품에 적용할 수 없는 옵션입니다."))).toList();
        if (options.stream().filter(o -> o.group().equals("DESIGNATION")).count() > 1)
            throw CatalogIntakeService.bad("작가 지정은 하나만 선택할 수 있습니다.");
        int operators = options.stream().filter(o -> o.operatorsOverride() != null)
            .mapToInt(DemoProductSheet.Option::operatorsOverride).findFirst().orElse(product.operators())
            + options.stream().mapToInt(DemoProductSheet.Option::additionalOperators).sum();
        if (options.stream().anyMatch(o -> o.unit() == DemoProductSheet.Unit.PER_HOUR_PER_OPERATOR)
                && extensionOperators > operators) throw CatalogIntakeService.bad("연장 작가 수는 선택한 촬영 인원을 초과할 수 없습니다.");
        var lines = new ArrayList<Line>();
        lines.add(new Line(product.name(), product.amount(), 1, product.amount()));
        for (var o : options) {
            int quantity = o.unit() == DemoProductSheet.Unit.PER_HOUR_PER_OPERATOR ? hours * extensionOperators : 1;
            lines.add(new Line(o.name(), o.amount(), quantity, Math.multiplyExact(o.amount(), quantity)));
        }
        long subtotal = lines.stream().mapToLong(Line::amount).reduce(0, Math::addExact);
        Long min = null, max = null;
        var unresolved = new ArrayList<String>();
        if (travelChoice == TravelChoice.NOT_APPLICABLE) { min = 0L; max = 0L; }
        else if (travelChoice == TravelChoice.APPLIES && sheet.travel().minimum() != null) {
            min = sheet.travel().minimum(); max = sheet.travel().maximum();
            unresolved.add("출장비 범위 내 실제 금액은 업체 확인이 필요합니다.");
        } else unresolved.add("출장비 적용 여부 또는 금액 미확인");
        if (product.taxStatus() != CatalogDetails.TaxStatus.INCLUDED) unresolved.add("부가세 포함 여부 또는 최종 세액 미확인");
        unresolved.add("사용자가 제공한 자료의 참고 계산입니다. 적용 날짜·요일·시각·유효 기간·예약 가능 여부와 최신 가격은 미확인입니다.");
        return new Estimate(true, product.id(), List.copyOf(lines), subtotal, min, max,
            min == null ? null : Math.addExact(subtotal, min), max == null ? null : Math.addExact(subtotal, max),
            operators, product.taxStatus(), List.copyOf(unresolved));
    }
}
