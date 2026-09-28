-- Reviewed detail projections. The approved publication JSON remains the versioned
-- public read model; these queryable rows are replaced in the SAME transaction.
-- Reference quotes are observed offers for exact conditions, not pricing rules.
CREATE TABLE catalog.listing_detail (
 listing_id uuid PRIMARY KEY REFERENCES catalog.listing(id),
 description text NOT NULL CHECK(length(description)<=2000),
 parking_spaces integer CHECK(parking_spaces BETWEEN 0 AND 100000),
 parking_free_minutes integer CHECK(parking_free_minutes BETWEEN 0 AND 10080),
 parking_fee_description text CHECK(length(parking_fee_description)<=1000),
 valet_available boolean,
 parking_access_description text CHECK(length(parking_access_description)<=1000),
 shuttle_description text CHECK(length(shuttle_description)<=1000)
);
CREATE TABLE catalog.reference_photo (
 listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 position integer NOT NULL CHECK(position BETWEEN 0 AND 11),
 url text NOT NULL CHECK(length(url)<=1000 AND url LIKE 'https://%'),
 caption text NOT NULL CHECK(length(caption) BETWEEN 1 AND 200),
 credit text NOT NULL CHECK(length(credit) BETWEEN 1 AND 200),
 source_url text NOT NULL CHECK(length(source_url)<=1000 AND source_url LIKE 'https://%'),
 rights_basis text NOT NULL CHECK(rights_basis IN ('OWNED','PERMISSION','LICENSED')),
 rights_confirmed boolean NOT NULL CHECK(rights_confirmed),
 PRIMARY KEY(listing_id,position)
);
CREATE TABLE catalog.reference_quote (
 listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 position integer NOT NULL CHECK(position BETWEEN 0 AND 23),
 title text NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
 service_date date NOT NULL CHECK(service_date BETWEEN DATE '2020-01-01' AND DATE '2100-12-31'),
 start_time time NOT NULL CHECK(extract(second FROM start_time)=0 AND start_time<TIME '24:00'),
 guests integer CHECK(guests BETWEEN 1 AND 10000),
 minimum_guests integer CHECK(minimum_guests BETWEEN 1 AND 10000),
 amount bigint NOT NULL CHECK(amount BETWEEN 0 AND 100000000000),
 tax_status text NOT NULL CHECK(tax_status IN ('INCLUDED','EXCLUDED','UNKNOWN')),
 included text NOT NULL CHECK(length(included) BETWEEN 1 AND 1200),
 excluded text NOT NULL CHECK(length(excluded) BETWEEN 1 AND 1200),
 conditions text CHECK(length(conditions)<=1200),
 source_url text NOT NULL CHECK(length(source_url)<=1000 AND source_url LIKE 'https://%'),
 checked_on date NOT NULL CHECK(checked_on>=DATE '2020-01-01'),
 valid_until date CHECK(valid_until>=checked_on),
 CHECK(minimum_guests IS NULL OR guests IS NOT NULL),
 PRIMARY KEY(listing_id,position)
);
CREATE INDEX ix_reference_quote_scenario ON catalog.reference_quote(service_date,start_time,guests,listing_id);
