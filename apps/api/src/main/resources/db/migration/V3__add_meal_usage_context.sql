ALTER TABLE meal_usages
    ADD COLUMN store_id UUID NOT NULL,
    ADD COLUMN meal_contract_id UUID NOT NULL,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL;
