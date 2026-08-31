ALTER TABLE meal_usage_qr_operation_audits
    DROP CONSTRAINT ck_meal_usage_qr_operation_audits_action,
    ADD CONSTRAINT ck_meal_usage_qr_operation_audits_action CHECK (
        action IN ('QR_ISSUED', 'QR_REVOKED', 'QR_RENEWED', 'PARTNER_QR_ENABLED', 'PARTNER_QR_DISABLED')
    ),
    DROP CONSTRAINT ck_meal_usage_qr_operation_audits_payload,
    ADD CONSTRAINT ck_meal_usage_qr_operation_audits_payload CHECK (
        (
            action IN ('QR_ISSUED', 'QR_REVOKED', 'QR_RENEWED')
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
    );
