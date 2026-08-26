CREATE TABLE pos_settlement_receipts (
    id UUID PRIMARY KEY,
    pos_settlement_id UUID NOT NULL UNIQUE REFERENCES pos_settlements (id),
    store_id UUID NOT NULL,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    scan_status VARCHAR(32) NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_pos_settlement_receipts_size_positive CHECK (size_bytes > 0),
    CONSTRAINT ck_pos_settlement_receipts_scan_clean CHECK (scan_status = 'CLEAN'),
    CONSTRAINT ck_pos_settlement_receipts_expiry_after_upload CHECK (expires_at = uploaded_at + interval '365 days')
);

CREATE INDEX idx_pos_settlement_receipts_expiry
    ON pos_settlement_receipts (expires_at, id)
    WHERE deleted_at IS NULL;
