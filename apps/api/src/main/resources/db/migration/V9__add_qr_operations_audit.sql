CREATE UNIQUE INDEX ux_meal_usage_qr_contexts_current_store
    ON meal_usage_qr_contexts (store_id)
    WHERE revoked_at IS NULL;

CREATE TABLE meal_usage_qr_operation_audits (
    id UUID PRIMARY KEY,
    action VARCHAR(40) NOT NULL,
    operator_id VARCHAR(120) NOT NULL,
    store_id UUID NOT NULL,
    qr_context_id UUID REFERENCES meal_usage_qr_contexts (id),
    meal_contract_id UUID REFERENCES meal_contracts (id),
    qr_selectable_before BOOLEAN,
    qr_selectable_after BOOLEAN,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_meal_usage_qr_operation_audits_action CHECK (
        action IN ('QR_ISSUED', 'QR_REVOKED', 'PARTNER_QR_ENABLED', 'PARTNER_QR_DISABLED')
    ),
    CONSTRAINT ck_meal_usage_qr_operation_audits_operator_nonblank CHECK (btrim(operator_id) <> ''),
    CONSTRAINT ck_meal_usage_qr_operation_audits_payload CHECK (
        (
            action IN ('QR_ISSUED', 'QR_REVOKED')
            AND qr_context_id IS NOT NULL
            AND meal_contract_id IS NULL
            AND qr_selectable_before IS NULL
            AND qr_selectable_after IS NULL
        )
        OR (
            action IN ('PARTNER_QR_ENABLED', 'PARTNER_QR_DISABLED')
            AND qr_context_id IS NULL
            AND meal_contract_id IS NOT NULL
            AND qr_selectable_before IS NOT NULL
            AND qr_selectable_after IS NOT NULL
            AND qr_selectable_before <> qr_selectable_after
        )
    )
);

CREATE INDEX idx_meal_usage_qr_operation_audits_store_occurred_at
    ON meal_usage_qr_operation_audits (store_id, occurred_at DESC);
