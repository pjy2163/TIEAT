ALTER TABLE public_meal_usage_idempotency_keys
    ADD COLUMN request_key_hash VARCHAR(64),
    ADD CONSTRAINT ck_public_meal_usage_idempotency_request_key_hash
        CHECK (request_key_hash IS NULL OR request_key_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE meal_usages
    ADD COLUMN customer_name TEXT;

CREATE INDEX ix_meal_usages_customer_name_retention
    ON meal_usages (created_at)
    WHERE customer_name IS NOT NULL;

CREATE TABLE customer_name_anonymization_audits (
    id UUID PRIMARY KEY,
    cutoff_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    affected_count INTEGER NOT NULL,
    CONSTRAINT ck_customer_name_anonymization_audits_affected_count CHECK (affected_count >= 0)
);
