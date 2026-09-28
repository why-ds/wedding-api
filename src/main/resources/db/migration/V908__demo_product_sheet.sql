-- Reference samples never become approved catalog offers through this table.
CREATE TABLE search.demo_product_sheet (
    listing_id uuid PRIMARY KEY REFERENCES search.demo_catalog_entry(id),
    data jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object'),
    CHECK (data->>'sourceKind' IS NOT NULL AND data->>'sourceKind' = 'USER_PROVIDED_UNVERIFIED'),
    CHECK (jsonb_typeof(data->'products') = 'array' AND jsonb_array_length(data->'products') BETWEEN 1 AND 20),
    CHECK (jsonb_typeof(data->'options') = 'array' AND jsonb_array_length(data->'options') <= 30)
);
COMMENT ON TABLE search.demo_product_sheet IS 'Unverified user-provided reference sheets for sample UI, separate from published pricing';
