CREATE TABLE pos_settlements (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    meal_contract_id UUID NOT NULL REFERENCES meal_contracts (id),
    pos_business_date DATE NOT NULL,
    submitted_total_minor BIGINT NOT NULL,
    recorded_by_login_id VARCHAR(120) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    idempotency_key UUID NOT NULL,
    CONSTRAINT ck_pos_settlements_submitted_total_positive CHECK (submitted_total_minor > 0),
    CONSTRAINT ck_pos_settlements_recorder_nonblank CHECK (btrim(recorded_by_login_id) <> ''),
    CONSTRAINT ux_pos_settlements_store_idempotency_key UNIQUE (store_id, idempotency_key)
);

CREATE TABLE pos_settlement_allocations (
    pos_settlement_id UUID NOT NULL REFERENCES pos_settlements (id),
    meal_usage_id UUID NOT NULL REFERENCES meal_usages (id),
    receivable_amount_minor BIGINT NOT NULL,
    PRIMARY KEY (pos_settlement_id, meal_usage_id),
    CONSTRAINT ux_pos_settlement_allocations_meal_usage UNIQUE (meal_usage_id),
    CONSTRAINT ck_pos_settlement_allocations_receivable_positive CHECK (receivable_amount_minor > 0)
);

CREATE INDEX idx_meal_usages_outstanding_receivable
    ON meal_usages (store_id, meal_contract_id, confirmed_at, id)
    WHERE status = 'CONFIRMED' AND receivable_created > 0;
