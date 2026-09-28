-- Synthetic-only preview fixture: stable across restarts and the 90-day real-price recheck window.
-- Restrict to the four fictional fixture IDs and explicit demo terms. Real offer validity is unchanged.
-- The fixed 2027 event-year contract and sell_during period remain in effect.
CREATE OR REPLACE VIEW search.demo_venue_projection AS
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
 AND l.id IN ('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000003','10000000-0000-4000-8000-000000000004')
 AND l.status='PUBLISHED' AND b.status='ACTIVE' AND org.status='ACTIVE'
 AND p.status='ACTIVE' AND pv.status='PUBLISHED' AND rev.status='PUBLISHED'
 AND o.status='ACTIVE' AND rev.sell_during @> now() AND rev.terms_snapshot @> '{"demo":true}'::jsonb
GROUP BY l.id,h.id,b.id,s.product_version_id,pv.id,rev.id;
