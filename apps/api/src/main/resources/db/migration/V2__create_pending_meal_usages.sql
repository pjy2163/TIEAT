CREATE TABLE meal_usages (
    id UUID PRIMARY KEY,
    entry_source VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT ck_meal_usages_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_meal_usages_entry_source CHECK (
        entry_source IN ('PARTNER_MOBILE', 'STORE_TABLET')
    ),
    CONSTRAINT ck_meal_usages_pending_only CHECK (status = 'PENDING')
);
