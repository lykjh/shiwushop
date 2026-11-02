CREATE INDEX idx_outbox_search_pending
    ON outbox_events (status, event_type, next_attempt_at, id);
