ALTER TABLE meal_usages
    DROP CONSTRAINT ck_meal_usages_pending_only,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN confirmed_staff_initials TEXT,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN prepaid_applied BIGINT,
    ADD COLUMN receivable_created BIGINT,
    ADD COLUMN remaining_prepaid BIGINT,
    ADD CONSTRAINT ck_meal_usages_status CHECK (status IN ('PENDING', 'CONFIRMED')),
    ADD CONSTRAINT ck_meal_usages_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT ck_meal_usages_confirmation_state CHECK (
        (
            status = 'PENDING'
            AND confirmed_staff_initials IS NULL
            AND confirmed_at IS NULL
            AND prepaid_applied IS NULL
            AND receivable_created IS NULL
            AND remaining_prepaid IS NULL
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
        )
    );
