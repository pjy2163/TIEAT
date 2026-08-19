CREATE TABLE store_catalog_entries (
    id UUID PRIMARY KEY,
    store_display_name VARCHAR(100) NOT NULL,
    brand_display_name VARCHAR(100) NOT NULL,
    logo_path VARCHAR(255),
    CONSTRAINT ck_store_catalog_entries_store_display_name_nonblank
        CHECK (btrim(store_display_name) <> ''),
    CONSTRAINT ck_store_catalog_entries_brand_display_name_nonblank
        CHECK (btrim(brand_display_name) <> '')
);

CREATE INDEX idx_store_catalog_entries_display_names
    ON store_catalog_entries (brand_display_name, store_display_name, id);

CREATE TABLE stores (
    id UUID PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    catalog_entry_id UUID REFERENCES store_catalog_entries (id),
    onboarding_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_stores_display_name_nonblank CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_stores_onboarding_status CHECK (
        onboarding_status IN ('PARTNER_REQUIRED', 'COMPLETE')
    )
);
