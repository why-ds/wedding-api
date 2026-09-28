-- Separate namespace and table: no FK to partner/listing/publication or favorites.
CREATE TABLE search.demo_catalog_entry (
 id uuid PRIMARY KEY,
 category_code text NOT NULL REFERENCES catalog.category(code),
 data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
 fixture_version text NOT NULL CHECK(fixture_version='catalog-demo-v1'),
 CHECK(data->>'category'=category_code),
 CHECK(data->>'externalKey' LIKE 'demo-%')
);
COMMENT ON TABLE search.demo_catalog_entry IS 'Synthetic UI fixtures only, never approved partner or pricing records';
