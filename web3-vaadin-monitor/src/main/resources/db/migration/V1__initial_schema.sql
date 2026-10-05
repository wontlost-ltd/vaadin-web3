CREATE TABLE merchants (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(255) NOT NULL, api_key_hash VARCHAR(64) NOT NULL UNIQUE,
 webhook_url VARCHAR(2048) NOT NULL, webhook_secret VARCHAR(128) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_intents (
 id VARCHAR(36) PRIMARY KEY, merchant_id VARCHAR(36) NOT NULL REFERENCES merchants(id), order_id VARCHAR(255) NOT NULL,
 chain_id BIGINT NOT NULL, token_symbol VARCHAR(32) NOT NULL, token_address VARCHAR(42) NOT NULL, token_decimals INTEGER NOT NULL,
 recipient VARCHAR(42) NOT NULL, amount_units VARCHAR(100) NOT NULL, payer VARCHAR(42), min_confirmations INTEGER NOT NULL,
 not_before TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(32) NOT NULL,
 tx_hash VARCHAR(66), paid_amount_units VARCHAR(100) NOT NULL, confirmations BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, lease_until TIMESTAMP WITH TIME ZONE, lease_token VARCHAR(36),
 attempts INTEGER NOT NULL, next_check_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE (merchant_id, order_id)
);
CREATE INDEX payment_intents_due_idx ON payment_intents (next_check_at, lease_until);
CREATE TABLE ledger_claims (claim_key VARCHAR(160) PRIMARY KEY, intent_id VARCHAR(36) NOT NULL);
CREATE TABLE webhook_deliveries (
 id VARCHAR(36) PRIMARY KEY, merchant_id VARCHAR(36) NOT NULL REFERENCES merchants(id), intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
 event_type VARCHAR(64) NOT NULL, payload TEXT NOT NULL, status VARCHAR(16) NOT NULL, attempts INTEGER NOT NULL,
 next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL, last_error VARCHAR(2000), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 delivered_at TIMESTAMP WITH TIME ZONE, lease_until TIMESTAMP WITH TIME ZONE, lease_token VARCHAR(36), UNIQUE (intent_id)
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (status, next_attempt_at);
