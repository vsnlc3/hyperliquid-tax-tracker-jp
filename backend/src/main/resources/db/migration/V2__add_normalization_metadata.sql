ALTER TABLE unified_transactions
    DROP CONSTRAINT unified_transactions_source_dataset_source_record_id_key;

ALTER TABLE unified_transactions
    ADD COLUMN related_transaction_id UUID REFERENCES unified_transactions(id),
    ADD COLUMN normalization_version TEXT NOT NULL DEFAULT 'bitbank-v1';

ALTER TABLE unified_transactions
    ADD CONSTRAINT unified_transactions_source_record_version_key
    UNIQUE (source, dataset, source_record_id, normalization_version);

CREATE INDEX unified_transactions_raw_data_version_idx
    ON unified_transactions (raw_data_id, normalization_version);
