CREATE UNIQUE INDEX price_snapshots_identity_idx
    ON price_snapshots (asset, currency, price_timestamp, source, provider_asset_id);
