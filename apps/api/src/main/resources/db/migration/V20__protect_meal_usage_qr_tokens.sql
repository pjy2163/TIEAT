ALTER TABLE meal_usage_qr_contexts
    ADD COLUMN token_ciphertext BYTEA,
    ADD COLUMN token_nonce BYTEA,
    ADD COLUMN token_key_version INTEGER,
    ADD CONSTRAINT ck_meal_usage_qr_contexts_token_protection CHECK (
        (
            token_ciphertext IS NULL
            AND token_nonce IS NULL
            AND token_key_version IS NULL
        )
        OR (
            token_ciphertext IS NOT NULL
            AND token_nonce IS NOT NULL
            AND token_key_version IS NOT NULL
            AND octet_length(token_nonce) = 12
            AND token_key_version > 0
        )
    );
