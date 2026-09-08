-- DEVELOPMENT ONLY. No real vendors, prices, addresses or reviews.
-- This fixture uses the original normalized catalog/pricing tables.
CREATE TEMP TABLE venue_fixture (
 id uuid, name text, hall text, region text, style text, capacity integer, guarantee integer,
 meal numeric, rental numeric, flowers numeric, weekend_extra numeric, evening_discount numeric,
 beverage_per_guest numeric, unknown_flowers boolean, features text[], description text
) ON COMMIT DROP;
INSERT INTO venue_fixture VALUES
('10000000-0000-4000-8000-000000000001','오브 그랜드','그랜드홀','강남','호텔',500,300,70000,4000000,2000000,0,1000000,5000,false,ARRAY['단독 예식','높은 층고','주차 300대'],'여유로운 공간과 차분한 분위기의 호텔형 홀입니다. 모든 정보는 개발용 가상 데이터입니다.'),
('10000000-0000-4000-8000-000000000002','메종 드 가든','가든홀','서초','가든',350,250,80000,5000000,1500000,0,1500000,6000,false,ARRAY['자연 채광','실내 가든','분리 예식'],'초록의 정원과 따뜻한 자연광을 담은 실내 가든형 홀입니다. 개발용 가상 업체입니다.'),
('10000000-0000-4000-8000-000000000003','아틀리에 서울','채플홀','송파','채플',300,200,65000,3500000,0,1000000,500000,4500,true,ARRAY['채플 예식','프라이빗 로비','주차 200대'],'간결한 채플 공간에서 두 사람에게 집중하는 예식입니다. 장식 비용은 확인이 필요합니다.'),
('10000000-0000-4000-8000-000000000004','더 리버 하우스','리버홀','영등포','하우스',180,100,95000,3000000,1000000,1500000,500000,7000,false,ARRAY['소규모 예식','테라스','단독 대관'],'가까운 사람들과 함께하는 소규모 하우스 예식 공간입니다. 개발용 가상 업체입니다.');

INSERT INTO pricing.cost_item(code,category_code,label) VALUES
('DEMO_WEEKEND','VENUE','가상 주말 추가금'),('DEMO_BEVERAGE','VENUE','가상 주류·음료');

DO $$
DECLARE
 r record; c record; source_id uuid; org_id uuid; branch_id uuid; hall_id uuid;
 product_id uuid; version_id uuid; offer_id uuid; revision_id uuid; component_id uuid; charge_id uuid;
BEGIN
 INSERT INTO ops.source_document(source_kind,title,private_object_key,observed_at,rights_basis)
 VALUES ('EDITOR','개발용 가상 가격표 — 실제 시세 아님','synthetic/venue-demo-v1',now(),'INTERNAL') RETURNING id INTO source_id;
 FOR r IN SELECT * FROM venue_fixture LOOP
  INSERT INTO partner.organization(legal_name,display_name) VALUES(r.name||' (가상)',r.name) RETURNING id INTO org_id;
  INSERT INTO partner.branch(organization_id,name,region_code,road_address)
  VALUES(org_id,r.region||' 가상 지점',r.region,'서울 '||r.region||'구 · 가상 주소') RETURNING id INTO branch_id;
  INSERT INTO catalog.listing(id,branch_id,category_code,name,slug,description,search_text,status)
  VALUES(r.id,branch_id,'VENUE',r.name,'demo-'||r.id,r.description,r.name||' '||r.region,'PUBLISHED');
  INSERT INTO catalog.venue_hall(listing_id,name,hall_style,max_event_guests)
  VALUES(r.id,r.hall,r.style,r.capacity) RETURNING id INTO hall_id;
  INSERT INTO catalog.product(listing_id,category_code) VALUES(r.id,'VENUE') RETURNING id INTO product_id;
  INSERT INTO catalog.product_version(product_id,listing_id,category_code,version_no,title,fulfillment_mode,service_phase,extension_attributes,source_document_id,status,published_at)
  VALUES(product_id,r.id,'VENUE',1,'가상 예식장 기본 구성','SERVICE','CEREMONY',jsonb_build_object('engine','venue-demo-v1','features',to_jsonb(r.features)),source_id,'PUBLISHED',now()) RETURNING id INTO version_id;
  INSERT INTO catalog.venue_spec(product_version_id,listing_id,hall_id,minimum_guarantee,guarantee_policy)
  VALUES(version_id,r.id,hall_id,r.guarantee,'ADULT_ONLY');
  INSERT INTO pricing.offer(seller_organization_id,title,offer_type,channel,status)
  VALUES(org_id,'가상 2027년 요금표','SINGLE','DIRECT','ACTIVE') RETURNING id INTO offer_id;
  INSERT INTO pricing.offer_revision(offer_id,seller_organization_id,revision_no,sell_during,status,source_document_id,verified_at,recheck_after,terms_snapshot,published_at)
  VALUES(offer_id,org_id,1,'[2026-01-01 00:00+09,2028-01-01 00:00+09)','PUBLISHED',source_id,now(),now()+interval '90 days','{"demo":true,"engine":"venue-demo-v1","tax":"INCLUDED","security":"UNKNOWN"}',now()) RETURNING id INTO revision_id;
  INSERT INTO pricing.offer_component(offer_revision_id,seller_organization_id,product_version_id,listing_id,category_code,component_key,event_key,inclusion_snapshot)
  VALUES(revision_id,org_id,version_id,r.id,'VENUE','venue','ceremony','{}') RETURNING id INTO component_id;
  FOR c IN SELECT * FROM (VALUES
   ('meal','MEAL_ADULT','성인 식사','REQUIRED','ADD','BILLED_ADULTS',r.meal,false),
   ('rental','HALL_RENTAL','대관료','REQUIRED','ADD','ONE',r.rental,false),
   ('flowers','FLOWERS','기본 꽃장식','REQUIRED','ADD','ONE',r.flowers,r.unknown_flowers),
   ('weekend','DEMO_WEEKEND','주말 추가금','CONDITIONAL','ADD','ONE',r.weekend_extra,false),
   ('evening','DISCOUNT','저녁 예식 할인','CONDITIONAL','DISCOUNT','ONE',r.evening_discount,false),
   ('beverages','DEMO_BEVERAGE','주류·음료','OPTIONAL','ADD','EXPECTED_ADULTS',r.beverage_per_guest,false)
  ) AS x(key,cost,label,requirement,effect,quantity,amount,unknown_amount) LOOP
   INSERT INTO pricing.charge(offer_revision_id,component_id,cost_item_code,charge_key,label,requirement,effect,quantity_key,event_key,activation_condition)
   VALUES(revision_id,component_id,c.cost,c.key,c.label,c.requirement,c.effect,c.quantity,'ceremony',jsonb_build_object('demoRule',c.key)) RETURNING id INTO charge_id;
   INSERT INTO pricing.charge_rule(offer_revision_id,charge_id,service_dates,local_minute_range,iso_weekdays,calculation,amount_kind,amount_min,amount_max,tax_mode,source_document_id)
   VALUES(revision_id,charge_id,'[2027-01-01,2028-01-01)',
     CASE WHEN c.key='evening' THEN '[1080,1440)'::int4range ELSE '[0,1440)'::int4range END,
     CASE WHEN c.key='weekend' THEN ARRAY[6,7]::smallint[] ELSE ARRAY[1,2,3,4,5,6,7]::smallint[] END,
     CASE WHEN c.quantity='ONE' THEN 'FIXED' ELSE 'PER_UNIT' END,
     CASE WHEN c.unknown_amount THEN 'ON_REQUEST' ELSE 'EXACT' END,
     CASE WHEN c.unknown_amount THEN NULL ELSE c.amount END, CASE WHEN c.unknown_amount THEN NULL ELSE c.amount END,
     'INCLUDED',source_id);
  END LOOP;
 END LOOP;
END $$;

-- Narrow adapter for the synthetic pricing engine, not a general charge_rule interpreter.
CREATE VIEW search.demo_venue_projection AS
SELECT l.id,l.name,h.name AS hall,b.region_code AS region,b.road_address AS address,h.hall_style AS style,
 h.max_event_guests AS capacity,s.minimum_guarantee AS guarantee,
 max(cr.amount_min) FILTER(WHERE c.charge_key='meal') AS meal,
 max(cr.amount_min) FILTER(WHERE c.charge_key='rental') AS rental,
 coalesce(max(cr.amount_min) FILTER(WHERE c.charge_key='flowers'),0) AS flowers,
 max(cr.amount_min) FILTER(WHERE c.charge_key='weekend') AS weekend_extra,
 max(cr.amount_min) FILTER(WHERE c.charge_key='evening') AS evening_discount,
 max(cr.amount_min) FILTER(WHERE c.charge_key='beverages') AS beverage_per_guest,
 bool_or(c.charge_key='flowers' AND cr.amount_kind='ON_REQUEST') AS unknown_flowers,
 ARRAY(SELECT jsonb_array_elements_text(pv.extension_attributes->'features')) AS features,l.description
FROM catalog.listing l
JOIN partner.branch b ON b.id=l.branch_id
JOIN partner.organization org ON org.id=b.organization_id
JOIN catalog.product p ON p.listing_id=l.id
JOIN catalog.product_version pv ON pv.product_id=p.id
JOIN catalog.venue_spec s ON s.product_version_id=pv.id
JOIN catalog.venue_hall h ON h.id=s.hall_id
JOIN pricing.offer_component oc ON oc.product_version_id=pv.id
JOIN pricing.offer_revision rev ON rev.id=oc.offer_revision_id
JOIN pricing.offer o ON o.id=rev.offer_id
JOIN pricing.charge c ON c.offer_revision_id=rev.id
JOIN pricing.charge_rule cr ON cr.charge_id=c.id
WHERE pv.extension_attributes->>'engine'='venue-demo-v1'
 AND l.status='PUBLISHED' AND b.status='ACTIVE' AND org.status='ACTIVE'
 AND p.status='ACTIVE' AND pv.status='PUBLISHED' AND rev.status='PUBLISHED'
 AND o.status='ACTIVE' AND rev.sell_during @> now() AND rev.recheck_after>now()
GROUP BY l.id,h.id,b.id,s.product_version_id,pv.id,rev.id;
