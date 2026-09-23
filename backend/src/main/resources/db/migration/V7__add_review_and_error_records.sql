CREATE TABLE classification_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    review_type TEXT NOT NULL CHECK (review_type IN (
        'TRANSACTION_CLASSIFICATION', 'TAX_EVENT_CLASSIFICATION'
    )),
    target_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE data_errors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    error_type TEXT NOT NULL CHECK (error_type IN ('DATA_COVERAGE', 'CALCULATION', 'IMPORT')),
    dataset TEXT,
    target_id UUID,
    code TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE recalculation_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    source_type TEXT NOT NULL,
    source_id UUID,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX classification_reviews_user_status_idx
    ON classification_reviews (user_id, status, review_type);
CREATE INDEX data_errors_user_status_idx
    ON data_errors (user_id, status, error_type);
CREATE INDEX recalculation_requests_user_status_idx
    ON recalculation_requests (user_id, status);
