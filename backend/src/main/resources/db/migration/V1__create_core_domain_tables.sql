CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE wallets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    network TEXT NOT NULL CHECK (network IN ('SOLANA')),
    address TEXT NOT NULL,
    label TEXT,
    wallet_type TEXT NOT NULL CHECK (wallet_type IN ('USER_WALLET')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, network, address)
);

CREATE TABLE hyperliquid_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    account_address TEXT NOT NULL,
    solana_deposit_address TEXT,
    account_mode TEXT NOT NULL CHECK (account_mode IN (
        'UNIFIED', 'STANDARD', 'PORTFOLIO_MARGIN', 'DISABLED', 'DEFAULT', 'DEX_ABSTRACTION', 'UNKNOWN'
    )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, account_address)
);

CREATE TABLE import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('BITBANK', 'SOLANA', 'HYPERLIQUID', 'COINGECKO')),
    dataset TEXT NOT NULL CHECK (dataset IN (
        'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
        'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER', 'PRICE_DATA'
    )),
    requested_from TIMESTAMPTZ,
    requested_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE raw_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source TEXT NOT NULL CHECK (source IN ('BITBANK', 'SOLANA', 'HYPERLIQUID', 'COINGECKO')),
    dataset TEXT NOT NULL CHECK (dataset IN (
        'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
        'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER', 'PRICE_DATA'
    )),
    external_id TEXT,
    occurred_at TIMESTAMPTZ,
    occurred_at_raw TEXT,
    timezone_confidence TEXT,
    payload JSONB NOT NULL,
    payload_hash TEXT NOT NULL,
    import_batch_id UUID NOT NULL REFERENCES import_batches(id),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, dataset, payload_hash)
);

CREATE TABLE unified_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('BITBANK', 'SOLANA', 'HYPERLIQUID', 'COINGECKO')),
    dataset TEXT NOT NULL CHECK (dataset IN (
        'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
        'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER', 'PRICE_DATA'
    )),
    source_record_id TEXT NOT NULL,
    occurred_at TIMESTAMPTZ,
    occurred_at_raw TEXT,
    transaction_type TEXT NOT NULL CHECK (transaction_type IN (
        'BUY', 'SELL', 'SWAP', 'TRANSFER_IN', 'TRANSFER_OUT', 'DEPOSIT',
        'WITHDRAWAL', 'PERP_FILL', 'FUNDING', 'FEE'
    )),
    asset_from TEXT,
    amount_from NUMERIC(38, 18),
    asset_to TEXT,
    amount_to NUMERIC(38, 18),
    asset TEXT,
    gross_amount NUMERIC(38, 18),
    net_amount NUMERIC(38, 18),
    fee_asset TEXT,
    fee_amount NUMERIC(38, 18),
    fee_type TEXT CHECK (fee_type IS NULL OR fee_type IN (
        'BITBANK_PURCHASE', 'BITBANK_WITHDRAWAL', 'SOLANA_NETWORK',
        'HYPERLIQUID_SPOT', 'HYPERLIQUID_PERPETUAL', 'HYPERLIQUID_WITHDRAWAL', 'OTHER'
    )),
    from_address TEXT,
    to_address TEXT,
    transaction_hash TEXT,
    raw_data_id UUID NOT NULL REFERENCES raw_data(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, dataset, source_record_id)
);

CREATE TABLE hyperliquid_fills (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL REFERENCES raw_data(id),
    coin TEXT NOT NULL,
    side TEXT,
    direction TEXT,
    size NUMERIC(38, 18),
    price NUMERIC(38, 18),
    occurred_at TIMESTAMPTZ,
    start_position NUMERIC(38, 18),
    crossed BOOLEAN,
    order_id TEXT,
    trade_id TEXT,
    hash TEXT,
    twap_id TEXT,
    UNIQUE (raw_data_id, trade_id)
);

CREATE TABLE hyperliquid_closed_pnl (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL REFERENCES raw_data(id),
    fill_id UUID REFERENCES hyperliquid_fills(id),
    coin TEXT NOT NULL,
    occurred_at TIMESTAMPTZ,
    closed_pnl NUMERIC(38, 18) NOT NULL,
    pnl_asset TEXT
);

CREATE TABLE hyperliquid_fees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL REFERENCES raw_data(id),
    related_fill_id UUID REFERENCES hyperliquid_fills(id),
    related_ledger_update_id UUID,
    fee_type TEXT NOT NULL CHECK (fee_type IN (
        'BITBANK_PURCHASE', 'BITBANK_WITHDRAWAL', 'SOLANA_NETWORK',
        'HYPERLIQUID_SPOT', 'HYPERLIQUID_PERPETUAL', 'HYPERLIQUID_WITHDRAWAL', 'OTHER'
    )),
    fee_asset TEXT,
    fee_amount NUMERIC(38, 18),
    occurred_at TIMESTAMPTZ
);

CREATE TABLE hyperliquid_funding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL REFERENCES raw_data(id),
    hash TEXT,
    coin TEXT NOT NULL,
    occurred_at TIMESTAMPTZ,
    funding_rate NUMERIC(38, 18),
    funding_amount NUMERIC(38, 18),
    position_size NUMERIC(38, 18),
    funding_asset TEXT,
    sample_count INTEGER
);

CREATE TABLE hyperliquid_ledger_updates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_data_id UUID NOT NULL REFERENCES raw_data(id),
    hash TEXT,
    subtype TEXT NOT NULL CHECK (subtype IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'OTHER')),
    ledger_update_type TEXT,
    token TEXT,
    amount NUMERIC(38, 18),
    usdc_value NUMERIC(38, 18),
    user_address TEXT,
    destination_address TEXT,
    fee NUMERIC(38, 18),
    native_token_fee NUMERIC(38, 18),
    nonce BIGINT,
    fee_asset TEXT,
    occurred_at TIMESTAMPTZ
);

ALTER TABLE hyperliquid_fees
    ADD CONSTRAINT hyperliquid_fees_ledger_fk
    FOREIGN KEY (related_ledger_update_id) REFERENCES hyperliquid_ledger_updates(id);

CREATE TABLE transfer_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outgoing_transaction_id UUID NOT NULL REFERENCES unified_transactions(id),
    incoming_transaction_id UUID NOT NULL REFERENCES unified_transactions(id),
    match_status TEXT NOT NULL CHECK (match_status IN ('MATCHED', 'POSSIBLE', 'UNMATCHED', 'REJECTED')),
    match_score NUMERIC(5, 4),
    matched_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (outgoing_transaction_id <> incoming_transaction_id)
);

CREATE UNIQUE INDEX transfer_links_one_confirmed_incoming_per_outgoing
    ON transfer_links (outgoing_transaction_id)
    WHERE match_status = 'MATCHED';

CREATE TABLE calculation_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    target_year INTEGER NOT NULL CHECK (target_year BETWEEN 2000 AND 2200),
    cost_basis_method TEXT NOT NULL CHECK (cost_basis_method IN ('TOTAL_AVERAGE', 'MOVING_AVERAGE', 'UNKNOWN')),
    opening_balance_id UUID,
    tax_rule_version TEXT NOT NULL,
    normalization_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'BLOCKED', 'FINAL')),
    blocked_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    calculated_at TIMESTAMPTZ
);

CREATE TABLE tax_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    calculation_run_id UUID NOT NULL REFERENCES calculation_runs(id),
    source_record_id UUID NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN (
        'ASSET_ACQUISITION', 'ASSET_DISPOSAL', 'SELF_TRANSFER', 'PERP_REALIZED_PNL',
        'FUNDING_RECEIVED', 'FUNDING_PAID', 'FEE'
    )),
    tax_status TEXT NOT NULL CHECK (tax_status IN ('TAXABLE', 'NON_TAXABLE', 'NEEDS_REVIEW')),
    asset TEXT,
    quantity NUMERIC(38, 18),
    jpy_value NUMERIC(38, 8),
    cost_basis_jpy NUMERIC(38, 8),
    profit_loss_jpy NUMERIC(38, 8),
    tax_rule_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE price_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset TEXT NOT NULL,
    currency TEXT NOT NULL,
    price NUMERIC(38, 18) NOT NULL,
    price_timestamp TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    provider_asset_id TEXT,
    request_from TIMESTAMPTZ,
    request_to TIMESTAMPTZ,
    interval TEXT,
    raw_data_id UUID REFERENCES raw_data(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cost_basis_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    asset TEXT NOT NULL,
    method TEXT NOT NULL CHECK (method IN ('TOTAL_AVERAGE', 'MOVING_AVERAGE', 'UNKNOWN')),
    effective_from DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cost_basis_opening_balances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    asset TEXT NOT NULL,
    as_of_date DATE NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    book_value_jpy NUMERIC(38, 8) NOT NULL,
    input_source TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, asset, as_of_date)
);

CREATE TABLE user_classifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    before_classification TEXT,
    after_classification TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE data_import_statuses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    dataset TEXT NOT NULL CHECK (dataset IN (
        'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
        'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER', 'PRICE_DATA'
    )),
    requested_from TIMESTAMPTZ,
    requested_to TIMESTAMPTZ,
    actual_from TIMESTAMPTZ,
    actual_to TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    reason TEXT,
    import_batch_id UUID REFERENCES import_batches(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE calculation_run_coverage (
    calculation_run_id UUID NOT NULL REFERENCES calculation_runs(id),
    dataset TEXT NOT NULL CHECK (dataset IN (
        'BITBANK_TRADES', 'BITBANK_WITHDRAWALS', 'SOLANA_TRANSACTIONS',
        'HYPERLIQUID_FILLS', 'HYPERLIQUID_FUNDING', 'HYPERLIQUID_LEDGER', 'PRICE_DATA'
    )),
    status TEXT NOT NULL CHECK (status IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    requested_from TIMESTAMPTZ,
    requested_to TIMESTAMPTZ,
    actual_from TIMESTAMPTZ,
    actual_to TIMESTAMPTZ,
    reason TEXT,
    PRIMARY KEY (calculation_run_id, dataset)
);

CREATE TABLE calculation_run_price_snapshots (
    calculation_run_id UUID NOT NULL REFERENCES calculation_runs(id),
    price_snapshot_id UUID NOT NULL REFERENCES price_snapshots(id),
    PRIMARY KEY (calculation_run_id, price_snapshot_id)
);

CREATE INDEX raw_data_import_batch_idx ON raw_data (import_batch_id);
CREATE INDEX unified_transactions_occurred_at_idx ON unified_transactions (occurred_at);
CREATE INDEX tax_events_calculation_run_idx ON tax_events (calculation_run_id);
CREATE INDEX data_import_statuses_dataset_idx ON data_import_statuses (dataset, status);
