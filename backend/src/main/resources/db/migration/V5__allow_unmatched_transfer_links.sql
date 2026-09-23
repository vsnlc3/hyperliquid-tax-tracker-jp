ALTER TABLE transfer_links
    ALTER COLUMN incoming_transaction_id DROP NOT NULL;

CREATE UNIQUE INDEX transfer_links_unique_pair_idx
    ON transfer_links (outgoing_transaction_id, incoming_transaction_id)
    WHERE incoming_transaction_id IS NOT NULL;

CREATE UNIQUE INDEX transfer_links_one_unmatched_outgoing_idx
    ON transfer_links (outgoing_transaction_id)
    WHERE incoming_transaction_id IS NULL AND match_status = 'UNMATCHED';

CREATE UNIQUE INDEX transfer_links_one_confirmed_incoming_idx
    ON transfer_links (incoming_transaction_id)
    WHERE incoming_transaction_id IS NOT NULL AND match_status = 'MATCHED';
