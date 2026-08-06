CREATE TABLE store_accounts (
    login_id VARCHAR(120) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    store_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL
);
