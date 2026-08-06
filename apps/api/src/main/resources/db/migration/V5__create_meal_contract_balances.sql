CREATE TABLE meal_contracts (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    payment_type VARCHAR(48) NOT NULL,
    prepaid_balance BIGINT NOT NULL,
    CONSTRAINT ck_meal_contracts_payment_type CHECK (
        payment_type IN ('POSTPAID', 'PREPAID_WITH_RECEIVABLE_OVERFLOW')
    ),
    CONSTRAINT ck_meal_contracts_prepaid_balance_nonnegative CHECK (prepaid_balance >= 0),
    CONSTRAINT ck_meal_contracts_postpaid_balance_zero CHECK (
        payment_type <> 'POSTPAID' OR prepaid_balance = 0
    )
);
