CREATE TABLE store_partner_registrations (
    store_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    partner_organization_id UUID NOT NULL REFERENCES partner_organizations (id),
    meal_contract_id UUID NOT NULL REFERENCES meal_contracts (id),
    partner_display_name VARCHAR(100) NOT NULL,
    payment_type VARCHAR(48) NOT NULL,
    initial_prepaid_balance_minor BIGINT NOT NULL,
    qr_selectable BOOLEAN NOT NULL,
    PRIMARY KEY (store_id, idempotency_key),
    CONSTRAINT ck_store_partner_registrations_partner_name_nonblank
        CHECK (btrim(partner_display_name) <> ''),
    CONSTRAINT ck_store_partner_registrations_payment_type CHECK (
        payment_type IN ('POSTPAID', 'PREPAID_WITH_RECEIVABLE_OVERFLOW')
    ),
    CONSTRAINT ck_store_partner_registrations_prepaid_balance_nonnegative
        CHECK (initial_prepaid_balance_minor >= 0),
    CONSTRAINT ck_store_partner_registrations_postpaid_balance_zero CHECK (
        payment_type <> 'POSTPAID' OR initial_prepaid_balance_minor = 0
    )
);
