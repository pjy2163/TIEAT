ALTER TABLE meal_usages
    DROP CONSTRAINT ck_meal_usages_confirmation_state,
    DROP CONSTRAINT ck_meal_usages_status,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(64),
    ADD CONSTRAINT ck_meal_usages_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
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
            AND cancelled_at IS NULL
            AND cancellation_reason IS NULL
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
            AND cancelled_at IS NULL
            AND cancellation_reason IS NULL
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
            AND cancelled_at IS NULL
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'CANCELLED'
            AND public_qr_context_id IS NOT NULL
            AND confirmed_staff_initials IS NULL
            AND confirmed_at IS NULL
            AND prepaid_applied IS NULL
            AND receivable_created IS NULL
            AND remaining_prepaid IS NULL
            AND rejected_staff_login_id IS NULL
            AND rejected_at IS NULL
            AND cancelled_at IS NOT NULL
            AND cancellation_reason = 'PUBLIC_SELF_CORRECTION'
        )
    );
