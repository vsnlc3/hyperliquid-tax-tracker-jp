ALTER TABLE import_batches DROP CONSTRAINT import_batches_dataset_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_dataset_check CHECK (dataset IN (
    'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
    'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER',
    'HYPERLIQUID_SPOT_METADATA', 'PRICE_DATA'
));

ALTER TABLE raw_data DROP CONSTRAINT raw_data_dataset_check;
ALTER TABLE raw_data ADD CONSTRAINT raw_data_dataset_check CHECK (dataset IN (
    'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
    'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER',
    'HYPERLIQUID_SPOT_METADATA', 'PRICE_DATA'
));

ALTER TABLE unified_transactions DROP CONSTRAINT unified_transactions_dataset_check;
ALTER TABLE unified_transactions ADD CONSTRAINT unified_transactions_dataset_check CHECK (dataset IN (
    'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
    'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER',
    'HYPERLIQUID_SPOT_METADATA', 'PRICE_DATA'
));

ALTER TABLE data_import_statuses DROP CONSTRAINT data_import_statuses_dataset_check;
ALTER TABLE data_import_statuses ADD CONSTRAINT data_import_statuses_dataset_check CHECK (dataset IN (
    'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
    'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER',
    'HYPERLIQUID_SPOT_METADATA', 'PRICE_DATA'
));

ALTER TABLE calculation_run_coverage DROP CONSTRAINT calculation_run_coverage_dataset_check;
ALTER TABLE calculation_run_coverage ADD CONSTRAINT calculation_run_coverage_dataset_check CHECK (dataset IN (
    'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
    'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER',
    'HYPERLIQUID_SPOT_METADATA', 'PRICE_DATA'
));

ALTER TABLE hyperliquid_fills ADD COLUMN external_key TEXT;
ALTER TABLE hyperliquid_fills ADD CONSTRAINT hyperliquid_fills_external_key_unique UNIQUE (external_key);

ALTER TABLE hyperliquid_fees ADD COLUMN external_key TEXT;
ALTER TABLE hyperliquid_fees ADD CONSTRAINT hyperliquid_fees_external_key_unique UNIQUE (external_key);

ALTER TABLE hyperliquid_funding ADD COLUMN external_key TEXT;
ALTER TABLE hyperliquid_funding ADD CONSTRAINT hyperliquid_funding_external_key_unique UNIQUE (external_key);

ALTER TABLE hyperliquid_ledger_updates ADD COLUMN external_key TEXT;
ALTER TABLE hyperliquid_ledger_updates ADD CONSTRAINT hyperliquid_ledger_external_key_unique UNIQUE (external_key);

ALTER TABLE hyperliquid_closed_pnl ADD CONSTRAINT hyperliquid_closed_pnl_fill_unique UNIQUE (fill_id);

CREATE TABLE hyperliquid_spot_metadata_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL UNIQUE REFERENCES raw_data(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE hyperliquid_spot_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metadata_snapshot_id UUID NOT NULL REFERENCES hyperliquid_spot_metadata_snapshots(id),
    token_index INTEGER NOT NULL,
    name TEXT NOT NULL,
    size_decimals INTEGER,
    wei_decimals INTEGER,
    token_id TEXT,
    is_canonical BOOLEAN,
    full_name TEXT,
    raw_payload JSONB NOT NULL,
    UNIQUE (metadata_snapshot_id, token_index)
);

CREATE TABLE hyperliquid_spot_pairs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metadata_snapshot_id UUID NOT NULL REFERENCES hyperliquid_spot_metadata_snapshots(id),
    pair_index INTEGER NOT NULL,
    name TEXT,
    base_token_index INTEGER NOT NULL,
    quote_token_index INTEGER NOT NULL,
    is_canonical BOOLEAN,
    raw_payload JSONB NOT NULL,
    UNIQUE (metadata_snapshot_id, pair_index)
);
