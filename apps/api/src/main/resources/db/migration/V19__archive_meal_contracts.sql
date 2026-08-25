ALTER TABLE meal_contracts
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archived_by_login_id VARCHAR(120),
    ADD CONSTRAINT ck_meal_contracts_archive_actor CHECK (
        archived_at IS NULL OR (archived_by_login_id IS NOT NULL AND btrim(archived_by_login_id) <> '')
    );

CREATE INDEX idx_meal_contracts_active_store
    ON meal_contracts (store_id, id)
    WHERE archived_at IS NULL;
