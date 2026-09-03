ALTER TABLE pos_settlement_receipts
    DROP CONSTRAINT ck_pos_settlement_receipts_scan_clean;

ALTER TABLE pos_settlement_receipts
    RENAME COLUMN scan_status TO validation_status;

UPDATE pos_settlement_receipts
SET validation_status = 'VALIDATED'
WHERE validation_status = 'CLEAN';

ALTER TABLE pos_settlement_receipts
    ADD CONSTRAINT ck_pos_settlement_receipts_validated
    CHECK (validation_status = 'VALIDATED');
