CREATE TABLE auth_abuse_rate_limits (
    scope VARCHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    blocked_until TIMESTAMPTZ,
    CONSTRAINT auth_abuse_rate_limits_pk PRIMARY KEY (scope, key_hash),
    CONSTRAINT auth_abuse_rate_limits_failed_attempts_positive CHECK (failed_attempts > 0)
);

CREATE INDEX ix_auth_abuse_rate_limits_window_started_at
    ON auth_abuse_rate_limits (window_started_at);
