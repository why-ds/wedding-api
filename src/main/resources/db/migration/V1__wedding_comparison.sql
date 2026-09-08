-- 웨딩 검색·비교 플랫폼: 설계용 초기 마이그레이션 v1.0 / 2026-09-07
-- PostgreSQL 18 기준. 새 전용 데이터베이스에서 migration owner로 1회 실행.
-- DROP/TRUNCATE 없음. 애플리케이션, 인증/RLS 정책, 전체 가격 계산기는 포함하지 않음.
-- 통화는 KRW. 원 단위 최종액, 중량·세율·계산 중간값은 numeric 사용.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE SCHEMA iam;
CREATE SCHEMA partner;
CREATE SCHEMA catalog;
CREATE SCHEMA pricing;
CREATE SCHEMA planning;
CREATE SCHEMA engagement;
CREATE SCHEMA scheduling;
CREATE SCHEMA ops;
CREATE SCHEMA search;

CREATE TABLE iam.user_account (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 display_name text NOT NULL,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED','WITHDRAWN')),
 created_at timestamptz NOT NULL DEFAULT now(), withdrawn_at timestamptz
);
CREATE TABLE iam.auth_identity (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 issuer text NOT NULL, subject text NOT NULL,
 UNIQUE(issuer,subject)
);
CREATE TABLE iam.user_contact (
 user_id uuid PRIMARY KEY REFERENCES iam.user_account(id),
 email_ciphertext bytea, phone_ciphertext bytea, key_version text NOT NULL,
 verified_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE partner.organization (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), legal_name text NOT NULL,
 display_name text NOT NULL, business_number text UNIQUE,
 verification_status text NOT NULL DEFAULT 'UNVERIFIED'
 CHECK(verification_status IN ('UNVERIFIED','VERIFIED','REJECTED')),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE partner.branch (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES partner.organization(id),
 name text NOT NULL, region_code text NOT NULL, road_address text NOT NULL,
 address_detail text, latitude numeric(10,7), longitude numeric(10,7),
 timezone text NOT NULL DEFAULT 'Asia/Seoul', public_phone text,
 parking_spaces integer CHECK(parking_spaces>=0), transit_description text,
 accessibility_description text,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','TEMP_CLOSED','CLOSED')),
 UNIQUE(id,organization_id),
 CHECK(latitude BETWEEN -90 AND 90), CHECK(longitude BETWEEN -180 AND 180),
 CHECK((latitude IS NULL)=(longitude IS NULL))
);
CREATE TABLE partner.organization_member (
 organization_id uuid NOT NULL REFERENCES partner.organization(id),
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 role text NOT NULL CHECK(role IN ('OWNER','EDITOR','CONSULTANT','VIEWER')),
 branch_id uuid, status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 PRIMARY KEY(organization_id,user_id),
 FOREIGN KEY(branch_id,organization_id) REFERENCES partner.branch(id,organization_id)
);
CREATE TABLE iam.consent_record (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES iam.user_account(id),
 purpose text NOT NULL CHECK(purpose IN ('TERMS','PRIVACY_COLLECTION','INQUIRY_DISCLOSURE','MARKETING')),
 recipient_organization_id uuid REFERENCES partner.organization(id),
 document_version text NOT NULL, disclosure_snapshot jsonb NOT NULL,
 granted_at timestamptz NOT NULL, withdrawn_at timestamptz,
 CHECK(purpose<>'INQUIRY_DISCLOSURE' OR recipient_organization_id IS NOT NULL),
 CHECK(withdrawn_at IS NULL OR withdrawn_at>=granted_at)
);

CREATE TABLE ops.source_document (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 source_kind text NOT NULL CHECK(source_kind IN ('OFFICIAL_DISCLOSURE','VENDOR','PARTNER','USER_EVIDENCE','EDITOR')),
 title text NOT NULL, source_url text, private_object_key text,
 content_sha256 text, observed_at timestamptz NOT NULL, published_on date,
 rights_basis text NOT NULL CHECK(rights_basis IN ('LINK_ONLY','FACTS_ALLOWED','LICENSED','USER_PERMISSION','INTERNAL')),
 visibility text NOT NULL DEFAULT 'PRIVATE' CHECK(visibility IN ('PRIVATE','PUBLIC_FACTS')),
 uploaded_by uuid REFERENCES iam.user_account(id), expires_at timestamptz,
 CHECK(source_url IS NOT NULL OR private_object_key IS NOT NULL),
 CHECK(expires_at IS NULL OR expires_at>observed_at)
);
CREATE TABLE ops.media_asset (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), object_key text NOT NULL UNIQUE,
 mime_type text NOT NULL, size_bytes bigint NOT NULL CHECK(size_bytes>0),
 source_document_id uuid REFERENCES ops.source_document(id),
 visibility text NOT NULL DEFAULT 'PRIVATE' CHECK(visibility IN ('PRIVATE','PUBLIC')),
 moderation_status text NOT NULL DEFAULT 'PENDING' CHECK(moderation_status IN ('PENDING','APPROVED','REJECTED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalog.category (
 code text PRIMARY KEY, name text NOT NULL, display_order integer NOT NULL UNIQUE
);
INSERT INTO catalog.category VALUES
 ('VENUE','예식장',1),('STUDIO','스튜디오',2),('DRESS','드레스',3),
 ('MAKEUP','헤어·메이크업',4),('JEWELRY','예물',5),('HANBOK','한복',6),('SUIT','남자정장·예복',7);
CREATE TABLE catalog.listing (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), branch_id uuid NOT NULL REFERENCES partner.branch(id),
 category_code text NOT NULL REFERENCES catalog.category(code), name text NOT NULL,
 slug text NOT NULL UNIQUE, description text, search_text text NOT NULL DEFAULT '',
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','SUSPENDED','CLOSED')),
 verified_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
 lock_version bigint NOT NULL DEFAULT 0,
 UNIQUE(id,category_code)
);
CREATE TABLE catalog.listing_media (
 listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 media_asset_id uuid NOT NULL REFERENCES ops.media_asset(id),
 caption text, position integer NOT NULL CHECK(position>=0),
 PRIMARY KEY(listing_id,media_asset_id), UNIQUE(listing_id,position)
);
CREATE TABLE catalog.listing_fact (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 fact_key text NOT NULL, value jsonb NOT NULL, source_document_id uuid NOT NULL REFERENCES ops.source_document(id),
 verified_at timestamptz, valid_until timestamptz,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED'))
);
CREATE TABLE catalog.tag (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), category_code text REFERENCES catalog.category(code),
 code text NOT NULL UNIQUE, label text NOT NULL
);
CREATE TABLE catalog.listing_tag (
 listing_id uuid NOT NULL REFERENCES catalog.listing(id), tag_id uuid NOT NULL REFERENCES catalog.tag(id),
 PRIMARY KEY(listing_id,tag_id)
);
CREATE TABLE catalog.venue_hall (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL,
 category_code text NOT NULL DEFAULT 'VENUE' CHECK(category_code='VENUE'),
 name text NOT NULL, floor_label text, hall_style text,
 seated_capacity integer CHECK(seated_capacity>0),
 max_event_guests integer CHECK(max_event_guests>0),
 banquet_capacity integer CHECK(banquet_capacity>0),
 ceremony_minutes integer CHECK(ceremony_minutes>0), interval_minutes integer CHECK(interval_minutes>0),
 exclusive_use boolean, indoor_outdoor text CHECK(indoor_outdoor IN ('INDOOR','OUTDOOR','MIXED')),
 rain_plan text, accessibility_description text,
 FOREIGN KEY(listing_id,category_code) REFERENCES catalog.listing(id,category_code),
 UNIQUE(id,listing_id), UNIQUE(listing_id,name)
);
CREATE TABLE catalog.product (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL,
 category_code text NOT NULL, status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISCONTINUED')),
 FOREIGN KEY(listing_id,category_code) REFERENCES catalog.listing(id,category_code),
 UNIQUE(id,listing_id,category_code)
);
CREATE TABLE catalog.product_version (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), product_id uuid NOT NULL,
 listing_id uuid NOT NULL, category_code text NOT NULL, version_no integer NOT NULL CHECK(version_no>0),
 title text NOT NULL, description text,
 fulfillment_mode text NOT NULL CHECK(fulfillment_mode IN ('SERVICE','RENTAL','PURCHASE','CUSTOM_PURCHASE','CUSTOM_RENTAL')),
 service_phase text NOT NULL CHECK(service_phase IN ('CEREMONY','SHOOTING','BOTH','FITTING','GENERAL')),
 lead_days_min integer CHECK(lead_days_min>=0), lead_days_max integer CHECK(lead_days_max>=lead_days_min),
 extension_attributes jsonb NOT NULL DEFAULT '{}',
 source_document_id uuid REFERENCES ops.source_document(id),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
 created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 FOREIGN KEY(product_id,listing_id,category_code) REFERENCES catalog.product(id,listing_id,category_code),
 UNIQUE(product_id,version_no), UNIQUE(id,category_code), UNIQUE(id,listing_id,category_code)
);
-- 업종 전용 스펙은 product_version에 1:1. 같은 상품의 과거 구성을 보존한다.
CREATE TABLE catalog.venue_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'VENUE' CHECK(category_code='VENUE'),
 listing_id uuid NOT NULL, hall_id uuid NOT NULL, menu_type text, menu_description text,
 minimum_guarantee integer CHECK(minimum_guarantee>=0),
 guarantee_policy text NOT NULL DEFAULT 'UNKNOWN'
 CHECK(guarantee_policy IN ('ADULT_ONLY','ALL_COUNT','WEIGHTED','MINIMUM_SPEND','UNKNOWN')),
 child_policy jsonb NOT NULL DEFAULT '{}', beverage_policy text, outside_vendor_policy text,
 FOREIGN KEY(product_version_id,listing_id,category_code) REFERENCES catalog.product_version(id,listing_id,category_code),
 FOREIGN KEY(hall_id,listing_id) REFERENCES catalog.venue_hall(id,listing_id)
);
CREATE TABLE catalog.studio_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'STUDIO' CHECK(category_code='STUDIO'),
 shooting_minutes integer CHECK(shooting_minutes>0), outfit_changes integer CHECK(outfit_changes>=0),
 album_pages integer CHECK(album_pages>=0), retouched_cuts integer CHECK(retouched_cuts>=0),
 raw_files text NOT NULL DEFAULT 'UNKNOWN' CHECK(raw_files IN ('INCLUDED','EXTRA','NOT_PROVIDED','UNKNOWN')),
 photo_style text, indoor_outdoor text, photographer_grade text,
 delivery_days_min integer CHECK(delivery_days_min>=0), delivery_days_max integer CHECK(delivery_days_max>=delivery_days_min),
 deliverables jsonb NOT NULL DEFAULT '{}', outside_hair_allowed boolean,
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code)
);
CREATE TABLE catalog.dress_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'DRESS' CHECK(category_code='DRESS'),
 collection_name text, line_grade text, silhouette text, fabric text,
 shooting_garments integer CHECK(shooting_garments>=0), ceremony_garments integer CHECK(ceremony_garments>=0),
 fitting_sessions integer CHECK(fitting_sessions>=0), first_wear boolean,
 size_description text, rental_days integer CHECK(rental_days>0), accessories jsonb NOT NULL DEFAULT '{}',
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code)
);
CREATE TABLE catalog.makeup_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'MAKEUP' CHECK(category_code='MAKEUP'),
 included_people integer CHECK(included_people>0), target_roles text[], artist_grade text,
 makeup_minutes integer CHECK(makeup_minutes>0), hair_included boolean, trial_included boolean,
 visit_service boolean, early_start_before time, late_start_after time,
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code)
);
CREATE TABLE catalog.jewelry_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'JEWELRY' CHECK(category_code='JEWELRY'),
 piece_count integer CHECK(piece_count>0), metal text, fineness_per_mille numeric(6,3) CHECK(fineness_per_mille BETWEEN 0 AND 1000),
 metal_weight_grams numeric(10,4) CHECK(metal_weight_grams>0),
 stone_type text, stone_origin text CHECK(stone_origin IN ('NATURAL','LAB_GROWN','SIMULANT','NONE','UNKNOWN')),
 stone_count integer CHECK(stone_count>=0), center_carat numeric(9,4) CHECK(center_carat>0),
 total_carat numeric(9,4) CHECK(total_carat>0), cut_grade text, color_grade text, clarity_grade text,
 grading_lab text, grading_scheme text, grading_scheme_version text, quality_grade text,
 report_description text, resizing_policy text, workmanship_description text,
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code)
);
CREATE TABLE catalog.hanbok_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'HANBOK' CHECK(category_code='HANBOK'),
 target_roles text[], garment_count integer CHECK(garment_count>0), fabric text,
 custom_fitting boolean, rental_days integer CHECK(rental_days>0),
 included_accessories jsonb NOT NULL DEFAULT '{}', return_policy text, cleaning_policy text,
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code)
);
CREATE TABLE catalog.suit_spec (
 product_version_id uuid PRIMARY KEY, category_code text NOT NULL DEFAULT 'SUIT' CHECK(category_code='SUIT'),
 tailoring_method text CHECK(tailoring_method IN ('READY_TO_WEAR','MTM','BESPOKE','UNKNOWN')),
 fabric_mill text, fabric_collection text, fabric_composition text,
 suit_count integer CHECK(suit_count>0), fitting_sessions integer CHECK(fitting_sessions>=0),
 shooting_rental_count integer CHECK(shooting_rental_count>=0),
 included_items jsonb NOT NULL DEFAULT '{}', alteration_policy text, rental_days integer CHECK(rental_days>0),
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code)
);

CREATE TABLE partner.supply_agreement (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 seller_organization_id uuid NOT NULL REFERENCES partner.organization(id),
 valid_during tstzrange NOT NULL CHECK(NOT isempty(valid_during)),
 status text NOT NULL CHECK(status IN ('PENDING','ACTIVE','REVOKED','EXPIRED')),
 evidence_source_id uuid REFERENCES ops.source_document(id),
 scope_snapshot jsonb NOT NULL, UNIQUE(id,listing_id,seller_organization_id)
);
CREATE TABLE pricing.cost_item (
 code text PRIMARY KEY, category_code text REFERENCES catalog.category(code),
 label text NOT NULL, description text
);
INSERT INTO pricing.cost_item(code,category_code,label) VALUES
 ('PRODUCT_BASE',NULL,'단품 기본 구성'),('PACKAGE_BASE',NULL,'패키지 기본 구성'),('HALL_RENTAL','VENUE','대관'),('MEAL_ADULT','VENUE','성인 식사'),
 ('MEAL_CHILD','VENUE','소인 식사'),('FLOWERS','VENUE','장식'),('RAW_FILES','STUDIO','원본 파일'),
 ('RETOUCH','STUDIO','보정'),('ALBUM_EXTRA','STUDIO','앨범 추가'),('HELPER','DRESS','도우미'),
 ('FITTING','DRESS','피팅'),('DRESS_UPGRADE','DRESS','드레스 변경'),('EARLY_START','MAKEUP','이른 시간 시작'),
 ('ARTIST_GRADE','MAKEUP','담당자 등급'),('TRAVEL',NULL,'출장'),('DELIVERY',NULL,'배송'),
 ('SERVICE_FEE',NULL,'봉사료'),('DISCOUNT',NULL,'할인'),('SECURITY_DEPOSIT',NULL,'반환형 보증금');
CREATE TABLE pricing.offer (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), seller_organization_id uuid NOT NULL REFERENCES partner.organization(id),
 title text NOT NULL, offer_type text NOT NULL CHECK(offer_type IN ('SINGLE','BUNDLE')),
 channel text NOT NULL CHECK(channel IN ('DIRECT','PLANNER','PLATFORM','EXHIBITION')),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','PAUSED','ENDED')),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,seller_organization_id)
);
CREATE TABLE pricing.offer_revision (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), offer_id uuid NOT NULL,
 seller_organization_id uuid NOT NULL, revision_no integer NOT NULL CHECK(revision_no>0),
 sell_during tstzrange NOT NULL CHECK(NOT isempty(sell_during)),
 currency char(3) NOT NULL DEFAULT 'KRW' CHECK(currency='KRW'),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
 source_document_id uuid NOT NULL REFERENCES ops.source_document(id), verified_at timestamptz NOT NULL,
 recheck_after timestamptz NOT NULL, terms_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 FOREIGN KEY(offer_id,seller_organization_id) REFERENCES pricing.offer(id,seller_organization_id),
 UNIQUE(offer_id,revision_no), UNIQUE(id,seller_organization_id),
 CHECK(recheck_after>verified_at),
 EXCLUDE USING gist(offer_id WITH =,sell_during WITH &&) WHERE(status='PUBLISHED')
);
CREATE TABLE pricing.offer_component (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), offer_revision_id uuid NOT NULL,
 seller_organization_id uuid NOT NULL, product_version_id uuid NOT NULL,
 listing_id uuid NOT NULL, category_code text NOT NULL, supply_agreement_id uuid,
 component_key text NOT NULL, quantity numeric(10,3) NOT NULL DEFAULT 1 CHECK(quantity>0),
 event_key text NOT NULL, inclusion_snapshot jsonb NOT NULL,
 FOREIGN KEY(offer_revision_id,seller_organization_id) REFERENCES pricing.offer_revision(id,seller_organization_id),
 FOREIGN KEY(product_version_id,listing_id,category_code) REFERENCES catalog.product_version(id,listing_id,category_code),
 FOREIGN KEY(supply_agreement_id,listing_id,seller_organization_id)
 REFERENCES partner.supply_agreement(id,listing_id,seller_organization_id),
 UNIQUE(offer_revision_id,component_key), UNIQUE(offer_revision_id,id)
);
-- MVP는 제공업체·상품이 모두 확정된 평면 패키지. 선택형 조합은 별도 revision/offer로 확정한다.
CREATE TABLE pricing.charge (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), offer_revision_id uuid NOT NULL REFERENCES pricing.offer_revision(id),
 component_id uuid, cost_item_code text NOT NULL REFERENCES pricing.cost_item(code),
 charge_key text NOT NULL, label text NOT NULL,
 requirement text NOT NULL CHECK(requirement IN ('INCLUDED','REQUIRED','CONDITIONAL','OPTIONAL')),
 effect text NOT NULL DEFAULT 'ADD' CHECK(effect IN ('ADD','DISCOUNT','REFUNDABLE_SECURITY')),
 quantity_key text NOT NULL, event_key text NOT NULL,
 activation_condition jsonb NOT NULL DEFAULT '{}',
 payer_role text, payee_organization_id uuid REFERENCES partner.organization(id),
 display_order integer NOT NULL DEFAULT 0,
 FOREIGN KEY(offer_revision_id,component_id) REFERENCES pricing.offer_component(offer_revision_id,id),
 UNIQUE(offer_revision_id,charge_key), UNIQUE(offer_revision_id,id),
 CHECK(requirement<>'INCLUDED' OR effect='ADD')
);
CREATE TABLE pricing.charge_rule (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), offer_revision_id uuid NOT NULL, charge_id uuid NOT NULL,
 service_dates daterange NOT NULL CHECK(NOT isempty(service_dates)),
 local_minute_range int4range NOT NULL DEFAULT '[0,1440)'::int4range,
 iso_weekdays smallint[] NOT NULL DEFAULT ARRAY[1,2,3,4,5,6,7]::smallint[],
 holiday_mode text NOT NULL DEFAULT 'ANY' CHECK(holiday_mode IN ('ANY','ONLY','EXCLUDE')),
 guest_range int4range NOT NULL DEFAULT '[0,)'::int4range CHECK(NOT isempty(guest_range)),
 priority integer NOT NULL DEFAULT 0,
 calculation text NOT NULL CHECK(calculation IN ('FIXED','PER_UNIT','PERCENT')),
 amount_kind text NOT NULL CHECK(amount_kind IN ('EXACT','RANGE','ON_REQUEST')),
 amount_min numeric(18,4), amount_max numeric(18,4),
 tax_mode text NOT NULL CHECK(tax_mode IN ('INCLUDED','EXCLUDED','EXEMPT','UNKNOWN')),
 tax_rate numeric(7,6),
 quantity_parameters jsonb NOT NULL DEFAULT '{}',
 source_document_id uuid NOT NULL REFERENCES ops.source_document(id),
 FOREIGN KEY(offer_revision_id,charge_id) REFERENCES pricing.charge(offer_revision_id,id),
 CHECK(local_minute_range <@ '[0,1440)'::int4range AND NOT isempty(local_minute_range)),
 CHECK(cardinality(iso_weekdays)>0 AND iso_weekdays <@ ARRAY[1,2,3,4,5,6,7]::smallint[]),
 CHECK(amount_min>=0 AND amount_max>=amount_min),
 CHECK((amount_kind='ON_REQUEST' AND amount_min IS NULL AND amount_max IS NULL)
 OR (amount_kind='EXACT' AND amount_min IS NOT NULL AND amount_max IS NOT NULL AND amount_max=amount_min)
 OR (amount_kind='RANGE' AND amount_min IS NOT NULL AND amount_max IS NOT NULL AND amount_max>amount_min)),
 CHECK(tax_rate BETWEEN 0 AND 1), CHECK(tax_mode<>'EXCLUDED' OR tax_rate IS NOT NULL),
 CHECK(calculation<>'PERCENT' OR amount_max<=100)
);
CREATE TABLE pricing.charge_relation (
 offer_revision_id uuid NOT NULL, from_charge_id uuid NOT NULL, to_charge_id uuid NOT NULL,
 relation_type text NOT NULL CHECK(relation_type IN ('REQUIRES','EXCLUDES','PERCENT_BASE')),
 PRIMARY KEY(offer_revision_id,from_charge_id,to_charge_id,relation_type),
 FOREIGN KEY(offer_revision_id,from_charge_id) REFERENCES pricing.charge(offer_revision_id,id),
 FOREIGN KEY(offer_revision_id,to_charge_id) REFERENCES pricing.charge(offer_revision_id,id),
 CHECK(from_charge_id<>to_charge_id)
);

CREATE TABLE planning.wedding_project (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text NOT NULL,
 created_by uuid NOT NULL REFERENCES iam.user_account(id),
 currency char(3) NOT NULL DEFAULT 'KRW' CHECK(currency='KRW'),
 total_budget numeric(18,0) CHECK(total_budget>=0),
 created_at timestamptz NOT NULL DEFAULT now(), archived_at timestamptz
);
CREATE TABLE planning.project_member (
 project_id uuid NOT NULL REFERENCES planning.wedding_project(id),
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 role text NOT NULL CHECK(role IN ('OWNER','EDITOR','VIEWER')),
 joined_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz,
 PRIMARY KEY(project_id,user_id)
);
CREATE TABLE planning.project_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), project_id uuid NOT NULL REFERENCES planning.wedding_project(id),
 event_key text NOT NULL, event_type text NOT NULL CHECK(event_type IN ('CEREMONY','SHOOTING','MAKEUP','CONSULTATION','FITTING','PICKUP','DELIVERY','RETURN')),
 preferred_dates daterange, local_minute_range int4range,
 confirmed_during tstzrange, timezone text NOT NULL DEFAULT 'Asia/Seoul',
 branch_id uuid REFERENCES partner.branch(id), custom_location text,
 expected_adults integer CHECK(expected_adults>=0), expected_children integer CHECK(expected_children>=0),
 status text NOT NULL DEFAULT 'TENTATIVE' CHECK(status IN ('TENTATIVE','CONFIRMED','DONE','CANCELLED')),
 UNIQUE(project_id,event_key), UNIQUE(project_id,id),
 CHECK(preferred_dates IS NULL OR NOT isempty(preferred_dates)),
 CHECK(confirmed_during IS NULL OR (NOT isempty(confirmed_during) AND NOT lower_inf(confirmed_during) AND NOT upper_inf(confirmed_during))),
 CHECK(local_minute_range IS NULL OR (NOT isempty(local_minute_range) AND local_minute_range <@ '[0,1440)'::int4range))
);
CREATE TABLE planning.event_dependency (
 project_id uuid NOT NULL, predecessor_event_id uuid NOT NULL, successor_event_id uuid NOT NULL,
 minimum_gap_minutes integer NOT NULL CHECK(minimum_gap_minutes>=0),
 basis text NOT NULL, source_document_id uuid REFERENCES ops.source_document(id),
 PRIMARY KEY(project_id,predecessor_event_id,successor_event_id),
 FOREIGN KEY(project_id,predecessor_event_id) REFERENCES planning.project_event(project_id,id),
 FOREIGN KEY(project_id,successor_event_id) REFERENCES planning.project_event(project_id,id),
 CHECK(predecessor_event_id<>successor_event_id)
);
CREATE TABLE planning.category_budget (
 project_id uuid NOT NULL REFERENCES planning.wedding_project(id), category_code text NOT NULL REFERENCES catalog.category(code),
 amount numeric(18,0) NOT NULL CHECK(amount>=0), PRIMARY KEY(project_id,category_code)
);
CREATE TABLE planning.shortlist (
 project_id uuid NOT NULL REFERENCES planning.wedding_project(id), listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 added_by uuid NOT NULL REFERENCES iam.user_account(id), note text,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(project_id,listing_id)
);
CREATE TABLE pricing.estimate (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), offer_revision_id uuid NOT NULL REFERENCES pricing.offer_revision(id),
 project_id uuid REFERENCES planning.wedding_project(id), created_by uuid REFERENCES iam.user_account(id),
 scenario_schema_version text NOT NULL, context_snapshot jsonb NOT NULL,
 context_hash text NOT NULL, comparison_context_hash text NOT NULL,
 comparison_scope text NOT NULL, engine_version text NOT NULL,
 state text NOT NULL CHECK(state IN ('COMPLETE_FIXED','COMPLETE_RANGE','PARTIAL','UNAVAILABLE')),
 total_min numeric(18,0), total_max numeric(18,0), known_subtotal numeric(18,0) NOT NULL,
 refundable_security numeric(18,0) NOT NULL DEFAULT 0 CHECK(refundable_security>=0),
 unknown_items jsonb NOT NULL DEFAULT '[]', assumptions jsonb NOT NULL DEFAULT '[]',
 source_snapshot jsonb NOT NULL, terms_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 UNIQUE(id,offer_revision_id),
 CHECK(expires_at>created_at), CHECK(total_min>=0 AND total_max>=total_min),
 CHECK((state='COMPLETE_FIXED' AND total_min IS NOT NULL AND total_max IS NOT NULL AND total_max=total_min)
 OR (state='COMPLETE_RANGE' AND total_min IS NOT NULL AND total_max IS NOT NULL AND total_max>total_min)
 OR (state IN ('PARTIAL','UNAVAILABLE') AND total_min IS NULL AND total_max IS NULL))
);
CREATE TABLE pricing.estimate_line (
 estimate_id uuid NOT NULL, line_no integer NOT NULL CHECK(line_no>0), offer_revision_id uuid NOT NULL,
 charge_id uuid NOT NULL, label_snapshot text NOT NULL, quantity numeric(14,4),
 amount_min numeric(18,0), amount_max numeric(18,0),
 calculation_snapshot jsonb NOT NULL,
 PRIMARY KEY(estimate_id,line_no),
 FOREIGN KEY(estimate_id,offer_revision_id) REFERENCES pricing.estimate(id,offer_revision_id),
 FOREIGN KEY(offer_revision_id,charge_id) REFERENCES pricing.charge(offer_revision_id,id),
 CHECK(quantity>=0), CHECK(amount_max>=amount_min)
);
CREATE TABLE planning.comparison (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), project_id uuid NOT NULL REFERENCES planning.wedding_project(id),
 title text NOT NULL, comparison_scope text NOT NULL, context_snapshot jsonb NOT NULL,
 context_hash text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE planning.comparison_item (
 comparison_id uuid NOT NULL REFERENCES planning.comparison(id), estimate_id uuid NOT NULL REFERENCES pricing.estimate(id),
 position integer NOT NULL CHECK(position BETWEEN 1 AND 5),
 PRIMARY KEY(comparison_id,estimate_id), UNIQUE(comparison_id,position)
);

CREATE TABLE scheduling.business_hour (
 branch_id uuid NOT NULL REFERENCES partner.branch(id), iso_weekday smallint NOT NULL CHECK(iso_weekday BETWEEN 1 AND 7),
 open_minute integer NOT NULL CHECK(open_minute BETWEEN 0 AND 1439),
 close_minute integer NOT NULL CHECK(close_minute BETWEEN 1 AND 1440),
 purpose text NOT NULL CHECK(purpose IN ('STORE','CONSULTATION')),
 PRIMARY KEY(branch_id,iso_weekday,open_minute,purpose), CHECK(close_minute>open_minute)
);
CREATE TABLE scheduling.business_hour_exception (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), branch_id uuid NOT NULL REFERENCES partner.branch(id),
 local_date date NOT NULL, purpose text NOT NULL CHECK(purpose IN ('STORE','CONSULTATION')),
 is_closed boolean NOT NULL, replacement_hours jsonb NOT NULL DEFAULT '[]', reason text,
 UNIQUE(branch_id,local_date,purpose)
);
CREATE TABLE scheduling.service_resource (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 hall_id uuid, kind text NOT NULL CHECK(kind IN ('HALL','ROOM','ARTIST','FITTING_ROOM','GARMENT','TEAM')),
 display_name text NOT NULL, capacity integer NOT NULL DEFAULT 1 CHECK(capacity>0),
 timezone text NOT NULL DEFAULT 'Asia/Seoul',
 FOREIGN KEY(hall_id,listing_id) REFERENCES catalog.venue_hall(id,listing_id),
 CHECK((kind='HALL')=(hall_id IS NOT NULL)), UNIQUE(id,listing_id)
);
CREATE TABLE scheduling.availability_observation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL REFERENCES catalog.listing(id), resource_id uuid,
 during tstzrange NOT NULL, status text NOT NULL CHECK(status IN ('REPORTED_AVAILABLE','UNAVAILABLE','ON_REQUEST')),
 source_document_id uuid NOT NULL REFERENCES ops.source_document(id),
 observed_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 external_reference text,
 FOREIGN KEY(resource_id,listing_id) REFERENCES scheduling.service_resource(id,listing_id),
 CHECK(NOT isempty(during) AND NOT lower_inf(during) AND NOT upper_inf(during)),
 CHECK(expires_at>observed_at)
);
CREATE TABLE scheduling.calendar_day (
 country_code char(2) NOT NULL, local_date date NOT NULL, is_holiday boolean NOT NULL, holiday_name text,
 source_document_id uuid NOT NULL REFERENCES ops.source_document(id),
 PRIMARY KEY(country_code,local_date)
);

CREATE TABLE engagement.inquiry (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), project_id uuid NOT NULL REFERENCES planning.wedding_project(id),
 requested_by uuid NOT NULL REFERENCES iam.user_account(id),
 offer_revision_id uuid NOT NULL, seller_organization_id uuid NOT NULL,
 estimate_id uuid, consent_record_id uuid NOT NULL REFERENCES iam.consent_record(id),
 request_snapshot jsonb NOT NULL, message text,
 status text NOT NULL DEFAULT 'SUBMITTED'
 CHECK(status IN ('SUBMITTED','ACKNOWLEDGED','QUOTED','CLOSED','WITHDRAWN')),
 idempotency_key text NOT NULL, request_hash text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), lock_version bigint NOT NULL DEFAULT 0,
 FOREIGN KEY(offer_revision_id,seller_organization_id) REFERENCES pricing.offer_revision(id,seller_organization_id),
 FOREIGN KEY(estimate_id,offer_revision_id) REFERENCES pricing.estimate(id,offer_revision_id),
 UNIQUE(requested_by,idempotency_key)
);
CREATE TABLE engagement.vendor_quote (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), inquiry_id uuid NOT NULL REFERENCES engagement.inquiry(id),
 revision_no integer NOT NULL CHECK(revision_no>0), submitted_by uuid NOT NULL REFERENCES iam.user_account(id),
 total_amount numeric(18,0) CHECK(total_amount>=0),
 completeness text NOT NULL CHECK(completeness IN ('COMPLETE','PARTIAL')),
 quote_snapshot jsonb NOT NULL, source_document_id uuid REFERENCES ops.source_document(id),
 created_at timestamptz NOT NULL DEFAULT now(), valid_until timestamptz NOT NULL,
 UNIQUE(inquiry_id,revision_no), CHECK(valid_until>created_at),
 CHECK(completeness<>'COMPLETE' OR total_amount IS NOT NULL)
);
CREATE TABLE engagement.experience (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), project_id uuid NOT NULL REFERENCES planning.wedding_project(id),
 listing_id uuid NOT NULL REFERENCES catalog.listing(id), service_phase text NOT NULL,
 occurred_on date NOT NULL, stage text NOT NULL CHECK(stage IN ('CONSULTED','CONTRACTED','FULFILLED')),
 evidence_source_id uuid NOT NULL REFERENCES ops.source_document(id),
 verification_status text NOT NULL DEFAULT 'PENDING' CHECK(verification_status IN ('PENDING','VERIFIED','REJECTED','REVOKED')),
 verified_by uuid REFERENCES iam.user_account(id), verified_at timestamptz,
 UNIQUE(project_id,listing_id,service_phase,occurred_on), UNIQUE(id,listing_id)
);
CREATE TABLE engagement.review (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), experience_id uuid NOT NULL UNIQUE,
 listing_id uuid NOT NULL, author_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 experience_stage_snapshot text NOT NULL CHECK(experience_stage_snapshot IN ('CONSULTED','CONTRACTED','FULFILLED')),
 overall_rating smallint NOT NULL CHECK(overall_rating BETWEEN 1 AND 5), body text NOT NULL,
 incentive_disclosure text, status text NOT NULL DEFAULT 'PENDING'
 CHECK(status IN ('PENDING','PUBLISHED','REJECTED','HIDDEN','DELETED')),
 vendor_response text, responded_by uuid REFERENCES iam.user_account(id), responded_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(experience_id,listing_id) REFERENCES engagement.experience(id,listing_id)
);
CREATE TABLE engagement.rating_criterion (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), category_code text NOT NULL REFERENCES catalog.category(code),
 code text NOT NULL, label text NOT NULL, UNIQUE(category_code,code)
);
CREATE TABLE engagement.review_rating (
 review_id uuid NOT NULL REFERENCES engagement.review(id), criterion_id uuid NOT NULL REFERENCES engagement.rating_criterion(id),
 score smallint NOT NULL CHECK(score BETWEEN 1 AND 5), PRIMARY KEY(review_id,criterion_id)
);
CREATE TABLE engagement.review_report (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), review_id uuid NOT NULL REFERENCES engagement.review(id),
 reported_by uuid NOT NULL REFERENCES iam.user_account(id), reason text NOT NULL,
 status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','DISMISSED','ACTIONED')),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(review_id,reported_by)
);
CREATE TABLE engagement.price_report (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 submitted_by uuid NOT NULL REFERENCES iam.user_account(id), source_document_id uuid NOT NULL REFERENCES ops.source_document(id),
 reported_amount numeric(18,0) CHECK(reported_amount>=0), price_scope text NOT NULL,
 scenario_snapshot jsonb NOT NULL, contracted_on date, service_on date,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','VERIFIED','REJECTED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ops.data_submission (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), submitted_by uuid NOT NULL REFERENCES iam.user_account(id),
 organization_id uuid REFERENCES partner.organization(id), listing_id uuid REFERENCES catalog.listing(id),
 source_document_id uuid NOT NULL REFERENCES ops.source_document(id), proposed_changes jsonb NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 reviewed_by uuid REFERENCES iam.user_account(id), reviewed_at timestamptz,
 review_note text, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ops.audit_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor_user_id uuid REFERENCES iam.user_account(id),
 action text NOT NULL, target_type text NOT NULL, target_id uuid NOT NULL,
 redacted_changes jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ops.outbox_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), aggregate_type text NOT NULL, aggregate_id uuid NOT NULL,
 event_type text NOT NULL, payload jsonb NOT NULL, event_version integer NOT NULL DEFAULT 1,
 occurred_at timestamptz NOT NULL DEFAULT now(), available_at timestamptz NOT NULL DEFAULT now(),
 processed_at timestamptz, attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 last_error text
);
CREATE TABLE search.listing_projection (
 listing_id uuid PRIMARY KEY REFERENCES catalog.listing(id), category_code text NOT NULL REFERENCES catalog.category(code),
 region_code text NOT NULL, title text NOT NULL, search_text text NOT NULL,
 published_review_count integer NOT NULL DEFAULT 0 CHECK(published_review_count>=0),
 fulfilled_review_count integer NOT NULL DEFAULT 0 CHECK(fulfilled_review_count>=0),
 rating_average numeric(4,3), ranking_score numeric(8,5), facets jsonb NOT NULL DEFAULT '{}',
 source_version bigint NOT NULL DEFAULT 0, rebuilt_at timestamptz NOT NULL DEFAULT now(),
 CHECK(rating_average BETWEEN 1 AND 5)
);
CREATE TABLE search.price_projection (
 offer_revision_id uuid NOT NULL REFERENCES pricing.offer_revision(id), context_hash text NOT NULL,
 engine_version text NOT NULL, estimate_id uuid NOT NULL,
 expires_at timestamptz NOT NULL,
 PRIMARY KEY(offer_revision_id,context_hash,engine_version),
 FOREIGN KEY(estimate_id,offer_revision_id) REFERENCES pricing.estimate(id,offer_revision_id)
);

-- 주요 조회 경로. 실행계획·데이터 분포를 확인하며 추가/삭제한다.
CREATE INDEX ix_branch_region ON partner.branch(region_code,status);
CREATE INDEX ix_listing_branch ON catalog.listing(branch_id,category_code);
CREATE INDEX ix_listing_trgm ON catalog.listing USING gin(search_text gin_trgm_ops);
CREATE INDEX ix_product_listing ON catalog.product(listing_id);
CREATE INDEX ix_product_version_product ON catalog.product_version(product_id,status);
CREATE INDEX ix_offer_seller ON pricing.offer(seller_organization_id,status);
CREATE INDEX ix_component_listing ON pricing.offer_component(listing_id,offer_revision_id);
CREATE INDEX ix_component_product ON pricing.offer_component(product_version_id);
CREATE INDEX ix_charge_rule_charge ON pricing.charge_rule(charge_id,priority DESC);
CREATE INDEX ix_charge_rule_dates ON pricing.charge_rule USING gist(service_dates);
CREATE INDEX ix_project_member_user ON planning.project_member(user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_estimate_context ON pricing.estimate(offer_revision_id,context_hash,engine_version);
CREATE INDEX ix_review_public ON engagement.review(listing_id,created_at DESC,id) WHERE status='PUBLISHED';
CREATE INDEX ix_inquiry_seller ON engagement.inquiry(seller_organization_id,status,created_at DESC);
CREATE INDEX ix_inquiry_project ON engagement.inquiry(project_id,created_at DESC);
CREATE INDEX ix_availability_range ON scheduling.availability_observation USING gist(listing_id,during);
CREATE INDEX ix_availability_expiry ON scheduling.availability_observation(expires_at);
CREATE INDEX ix_projection_filter ON search.listing_projection(category_code,region_code);
CREATE INDEX ix_projection_text ON search.listing_projection USING gin(search_text gin_trgm_ops);
CREATE INDEX ix_projection_facets ON search.listing_projection USING gin(facets);
CREATE INDEX ix_outbox_pending ON ops.outbox_event(available_at,occurred_at) WHERE processed_at IS NULL;
CREATE INDEX ix_audit_target ON ops.audit_event(target_type,target_id,occurred_at DESC);

-- 배포 앱 역할에 schema owner 권한을 주지 않는다. GRANT/RLS/PII 정책은 별도 migration.
-- 아래 문서화된 publish 검증·동의/권한 검사·스냅샷 불변성은 서비스 구현의 필수 gate.
-- 본 DDL은 FK/CHECK/UNIQUE/기간 EXCLUDE를 제공하며 모든 업무 규칙을 대체하지 않는다.
