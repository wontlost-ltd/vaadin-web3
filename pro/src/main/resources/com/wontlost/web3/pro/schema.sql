CREATE TABLE IF NOT EXISTS web3_pro_nonces (
  nonce VARCHAR(17) PRIMARY KEY,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS web3_pro_nonces_expiry_idx ON web3_pro_nonces (expires_at);
CREATE TABLE IF NOT EXISTS web3_pro_payment_claims (
  claim_key VARCHAR(160) PRIMARY KEY,
  order_id VARCHAR(255) NOT NULL
);
CREATE TABLE IF NOT EXISTS web3_pro_screening_audit (
  id VARCHAR(36) PRIMARY KEY,
  address VARCHAR(42) NOT NULL,
  allowed BOOLEAN NOT NULL,
  reason VARCHAR(2000),
  source VARCHAR(255) NOT NULL,
  checked_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS web3_pro_screening_address_time_idx ON web3_pro_screening_audit (address, checked_at);
CREATE TABLE IF NOT EXISTS web3_pro_payment_records (
  order_id VARCHAR(255) PRIMARY KEY,
  chain_id BIGINT NOT NULL,
  token_symbol VARCHAR(32) NOT NULL,
  token_address VARCHAR(42),
  amount VARCHAR(100) NOT NULL,
  payer VARCHAR(42),
  tx_hash VARCHAR(66),
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS web3_pro_payment_records_created_idx ON web3_pro_payment_records (created_at);
CREATE INDEX IF NOT EXISTS web3_pro_payment_records_status_idx ON web3_pro_payment_records (status, created_at);
