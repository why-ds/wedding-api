-- Additive: keep existing membership, draft and synthetic preview data intact.
INSERT INTO catalog.category(code,name,display_order) VALUES
 ('WEDDING_PHOTO','본식스냅',8),('IPHONE_SNAP','아이폰스냅',9),('WEDDING_VIDEO','DVD·본식영상',10);

CREATE TABLE ops.catalog_publication (
 draft_id uuid PRIMARY KEY REFERENCES ops.catalog_draft(id),
 listing_id uuid NOT NULL UNIQUE REFERENCES catalog.listing(id),
 source_document_id uuid NOT NULL REFERENCES ops.source_document(id),
 approved_data jsonb NOT NULL CHECK(jsonb_typeof(approved_data)='object'),
 draft_version bigint NOT NULL CHECK(draft_version>=0),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 reviewed_by uuid NOT NULL REFERENCES iam.user_account(id),
 reviewed_on date NOT NULL,
 published_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_catalog_publication_updated ON ops.catalog_publication(updated_at DESC,draft_id);
CREATE INDEX ix_listing_published_category ON catalog.listing(category_code,id) WHERE status='PUBLISHED';

-- Typed specs for the three new capture categories. Product/pricing editor is a later phase.
-- NULL is unknown, not false or free. Prices belong to pricing.charge, never these specs.
CREATE TABLE catalog.capture_spec (
 product_version_id uuid PRIMARY KEY,
 category_code text NOT NULL CHECK(category_code IN ('WEDDING_PHOTO','IPHONE_SNAP','WEDDING_VIDEO')),
 operator_count integer CHECK(operator_count BETWEEN 1 AND 50),
 camera_count integer CHECK(camera_count BETWEEN 1 AND 50),
 coverage_minutes integer CHECK(coverage_minutes BETWEEN 1 AND 2880),
 coverage_segments text[] CHECK(coverage_segments <@ ARRAY['PREPARATION','CEREMONY','FAMILY_PORTRAITS','RECEPTION','AFTER_PARTY']::text[]),
 delivery_days_min integer CHECK(delivery_days_min>=0),
 delivery_days_max integer CHECK(delivery_days_max>=0),
 raw_files text NOT NULL DEFAULT 'UNKNOWN' CHECK(raw_files IN ('INCLUDED','EXTRA','NOT_PROVIDED','UNKNOWN')),
 retouched_cuts integer CHECK(retouched_cuts>=0),
 album_pages integer CHECK(album_pages>=0),
 video_resolution text CHECK(video_resolution IN ('FHD','4K','8K','OTHER')),
 highlight_minutes numeric(6,2) CHECK(highlight_minutes>0),
 full_video_included boolean, original_audio_included boolean,
 same_day_preview boolean, backup_operator_available boolean,
 FOREIGN KEY(product_version_id,category_code) REFERENCES catalog.product_version(id,category_code),
 CHECK(delivery_days_max IS NULL OR (delivery_days_min IS NOT NULL AND delivery_days_max>=delivery_days_min)),
 CHECK(category_code<>'WEDDING_PHOTO' OR (video_resolution IS NULL AND highlight_minutes IS NULL AND full_video_included IS NULL AND original_audio_included IS NULL)),
 CHECK(coverage_segments IS NULL OR (cardinality(coverage_segments) BETWEEN 1 AND 5 AND array_position(coverage_segments,NULL) IS NULL))
);
