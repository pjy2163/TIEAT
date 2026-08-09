CREATE TABLE partner_organizations (
    id UUID PRIMARY KEY,
    display_name TEXT NOT NULL,
    CONSTRAINT ck_partner_organizations_display_name_nonblank CHECK (btrim(display_name) <> '')
);

ALTER TABLE meal_contracts
    ADD COLUMN partner_organization_id UUID,
    ADD COLUMN qr_selectable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT fk_meal_contracts_partner_organization
        FOREIGN KEY (partner_organization_id) REFERENCES partner_organizations (id),
    ADD CONSTRAINT ck_meal_contracts_qr_selectable_partner CHECK (
        NOT qr_selectable OR partner_organization_id IS NOT NULL
    );

CREATE UNIQUE INDEX ux_meal_contracts_qr_selectable_store_partner
    ON meal_contracts (store_id, partner_organization_id)
    WHERE qr_selectable;

CREATE TABLE meal_usage_qr_contexts (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    store_display_name TEXT NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_meal_usage_qr_contexts_store_display_name_nonblank CHECK (btrim(store_display_name) <> ''),
    CONSTRAINT ck_meal_usage_qr_contexts_expiry_after_created CHECK (expires_at > created_at)
);

ALTER TABLE meal_usages
    DROP CONSTRAINT ck_meal_usages_confirmation_state,
    DROP CONSTRAINT ck_meal_usages_status,
    ADD COLUMN partner_display_name TEXT,
    ADD COLUMN public_qr_context_id UUID,
    ADD COLUMN rejected_staff_login_id VARCHAR(120),
    ADD COLUMN rejected_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_meal_usages_public_qr_context
        FOREIGN KEY (public_qr_context_id) REFERENCES meal_usage_qr_contexts (id),
    ADD CONSTRAINT ck_meal_usages_public_qr_source CHECK (
        public_qr_context_id IS NULL
        OR (entry_source = 'PARTNER_MOBILE' AND partner_display_name IS NOT NULL AND btrim(partner_display_name) <> '')
    ),
    ADD CONSTRAINT ck_meal_usages_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    ADD CONSTRAINT ck_meal_usages_confirmation_state CHECK (
        (
            status = 'PENDING'
            AND confirmed_staff_initials IS NULL
            AND confirmed_at IS NULL
            AND prepaid_applied IS NULL
            AND receivable_created IS NULL
            AND remaining_prepaid IS NULL
            AND rejected_staff_login_id IS NULL
            AND rejected_at IS NULL
        )
        OR (
            status = 'CONFIRMED'
            AND confirmed_staff_initials IS NOT NULL
            AND confirmed_at IS NOT NULL
            AND prepaid_applied IS NOT NULL
            AND receivable_created IS NOT NULL
            AND remaining_prepaid IS NOT NULL
            AND prepaid_applied >= 0
            AND receivable_created >= 0
            AND remaining_prepaid >= 0
            AND prepaid_applied + receivable_created = amount
            AND rejected_staff_login_id IS NULL
            AND rejected_at IS NULL
        )
        OR (
            status = 'REJECTED'
            AND confirmed_staff_initials IS NULL
            AND confirmed_at IS NULL
            AND prepaid_applied IS NULL
            AND receivable_created IS NULL
            AND remaining_prepaid IS NULL
            AND rejected_staff_login_id IS NOT NULL
            AND btrim(rejected_staff_login_id) <> ''
            AND rejected_at IS NOT NULL
        )
    );

CREATE INDEX idx_meal_usages_public_qr_context_created_at
    ON meal_usages (public_qr_context_id, created_at)
    WHERE public_qr_context_id IS NOT NULL;

CREATE TABLE public_meal_usage_idempotency_keys (
    qr_context_id UUID NOT NULL REFERENCES meal_usage_qr_contexts (id),
    idempotency_key UUID NOT NULL,
    meal_contract_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    meal_usage_id UUID NOT NULL REFERENCES meal_usages (id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (qr_context_id, idempotency_key),
    CONSTRAINT ck_public_meal_usage_idempotency_amount_positive CHECK (amount > 0)
);
