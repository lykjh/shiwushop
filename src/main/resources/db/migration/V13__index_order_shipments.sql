CREATE INDEX idx_orders_expiry_scan
    ON orders (status, pay_expires_at, id);

CREATE INDEX idx_shipments_due_scan
    ON shipments (status, auto_confirm_at, id);