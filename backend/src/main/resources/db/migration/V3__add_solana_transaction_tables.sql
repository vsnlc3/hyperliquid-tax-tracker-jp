CREATE TABLE solana_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL UNIQUE REFERENCES raw_data(id),
    signature TEXT NOT NULL UNIQUE,
    slot BIGINT,
    block_time TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'NOT_FOUND', 'UNKNOWN')),
    signer_addresses JSONB NOT NULL DEFAULT '[]'::jsonb,
    version TEXT,
    network_fee_lamports BIGINT,
    priority_fee_lamports BIGINT,
    normalization_version TEXT NOT NULL DEFAULT 'solana-v1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE solana_system_transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    solana_transaction_id UUID NOT NULL REFERENCES solana_transactions(id),
    instruction_index INTEGER NOT NULL,
    source_address TEXT,
    destination_address TEXT,
    lamports BIGINT NOT NULL,
    UNIQUE (solana_transaction_id, instruction_index)
);

CREATE TABLE solana_token_transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    solana_transaction_id UUID NOT NULL REFERENCES solana_transactions(id),
    instruction_index INTEGER NOT NULL,
    source_address TEXT,
    destination_address TEXT,
    mint TEXT,
    raw_amount NUMERIC(38, 0),
    decimals INTEGER,
    ui_amount NUMERIC(38, 18),
    UNIQUE (solana_transaction_id, instruction_index)
);

CREATE INDEX solana_transactions_block_time_idx ON solana_transactions (block_time);
CREATE INDEX solana_system_transfers_destination_idx ON solana_system_transfers (destination_address);
CREATE INDEX solana_system_transfers_source_idx ON solana_system_transfers (source_address);
