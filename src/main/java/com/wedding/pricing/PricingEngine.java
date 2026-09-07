package com.wedding.pricing;

import com.wedding.catalog.Venue;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Pure, deterministic first slice. Synthetic rates are VAT-inclusive; no holiday rules. */
public class PricingEngine {
    public Estimate calculate(Venue v, SearchRequest r) {
        String context = "venue-demo-v1|" + r.date() + "|" + r.time() + "|" + r.guests() + "|" + r.beverages();
        var summary = Estimate.VenueSummary.from(v);
        if (r.date().isBefore(LocalDate.of(2027, 1, 1)) || r.date().isAfter(LocalDate.of(2027, 12, 31)) || r.guests() > v.capacity()) {
            String reason = r.guests() > v.capacity() ? "입력 인원이 행사 수용 인원을 초과합니다." : "가상 요금표는 2027년 예식만 지원합니다.";
            return new Estimate(summary, "UNAVAILABLE", null, null, "0", 0, List.of(), List.of(reason), "ON_REQUEST", "UNKNOWN", context, true);
        }
        var lines = new ArrayList<Estimate.Line>();
        int billed = Math.max(r.guests(), v.guarantee());
        add(lines, "성인 식사", billed, v.meal().multiply(BigDecimal.valueOf(billed)), "보증인원 " + v.guarantee() + "명 적용 · 부가세 포함");
        add(lines, "대관료", 1, v.rental(), "기본 대관 · 부가세 포함");
        var unknown = new ArrayList<String>();
        if (v.unknownFlowers()) unknown.add("필수 장식 비용 미확인");
        else add(lines, "기본 꽃장식", 1, v.flowers(), "필수 구성 · 부가세 포함");
        if (r.date().getDayOfWeek() == DayOfWeek.SATURDAY || r.date().getDayOfWeek() == DayOfWeek.SUNDAY)
            add(lines, "주말 추가금", 1, v.weekendExtra(), "토·일요일 적용 · 부가세 포함");
        if (r.time().getHour() >= 18) add(lines, "저녁 예식 할인", 1, v.eveningDiscount().negate(), "18시 이후 대관 할인");
        if (r.beverages()) add(lines, "주류·음료", r.guests(), v.beveragePerGuest().multiply(BigDecimal.valueOf(r.guests())), "예상 성인 인원 기준 · 부가세 포함");
        BigDecimal total = lines.stream().map(l -> new BigDecimal(l.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() < 0) throw new IllegalStateException("Negative estimate");
        boolean complete = unknown.isEmpty();
        return new Estimate(summary, complete ? "COMPLETE_FIXED" : "PARTIAL", complete ? total.toPlainString() : null,
            complete ? total.toPlainString() : null, total.toPlainString(), billed, lines, unknown,
            "ON_REQUEST", "UNKNOWN", context, true);
    }
    private void add(List<Estimate.Line> lines, String label, int quantity, BigDecimal amount, String explanation) {
        lines.add(new Estimate.Line(label, String.valueOf(quantity), amount.toPlainString(), explanation));
    }
}
