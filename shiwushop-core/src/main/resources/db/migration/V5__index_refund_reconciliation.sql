CREATE INDEX idx_refund_attempts_reconcile ON refund_attempts (status, updated_at, id);
