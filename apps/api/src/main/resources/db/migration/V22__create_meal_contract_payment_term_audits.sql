CREATE TABLE meal_contract_payment_term_audits (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    meal_contract_id UUID NOT NULL REFERENCES meal_contracts (id),
    actor_login_id VARCHAR(120) NOT NULL,
    previous_payment_type VARCHAR(48) NOT NULL,
    new_payment_type VARCHAR(48) NOT NULL,
    prepaid_balance_before BIGINT NOT NULL,
    prepaid_balance_after BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_meal_contract_payment_term_audits_actor_nonblank CHECK (btrim(actor_login_id) <> ''),
    CONSTRAINT ck_meal_contract_payment_term_audits_previous_type CHECK (
        previous_payment_type IN ('POSTPAID', 'PREPAID_WITH_RECEIVABLE_OVERFLOW')
    ),
    CONSTRAINT ck_meal_contract_payment_term_audits_new_type CHECK (
        new_payment_type IN ('POSTPAID', 'PREPAID_WITH_RECEIVABLE_OVERFLOW')
    ),
    CONSTRAINT ck_meal_contract_payment_term_audits_types_differ CHECK (
        previous_payment_type <> new_payment_type
    ),
    CONSTRAINT ck_meal_contract_payment_term_audits_balances_nonnegative CHECK (
        prepaid_balance_before >= 0 AND prepaid_balance_after >= 0
    ),
    CONSTRAINT ck_meal_contract_payment_term_audits_postpaid_after_zero CHECK (
        new_payment_type <> 'POSTPAID' OR prepaid_balance_after = 0
    )
);

CREATE INDEX idx_meal_contract_payment_term_audits_store_occurred_at
    ON meal_contract_payment_term_audits (store_id, occurred_at DESC);

CREATE INDEX idx_meal_contract_payment_term_audits_contract_occurred_at
    ON meal_contract_payment_term_audits (meal_contract_id, occurred_at DESC);
