CREATE TABLE store_archive_pin_security (
    store_id UUID PRIMARY KEY,
    pin_hash VARCHAR(100) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    failure_window_started_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_login_id VARCHAR(120) NOT NULL,
    CONSTRAINT ck_store_archive_pin_security_pin_hash_nonblank CHECK (btrim(pin_hash) <> ''),
    CONSTRAINT ck_store_archive_pin_security_failed_attempts CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_store_archive_pin_security_failure_window CHECK (
        (failed_attempts = 0 AND failure_window_started_at IS NULL)
        OR (failed_attempts > 0 AND failure_window_started_at IS NOT NULL)
    ),
    CONSTRAINT ck_store_archive_pin_security_actor_nonblank CHECK (btrim(updated_by_login_id) <> '')
);
