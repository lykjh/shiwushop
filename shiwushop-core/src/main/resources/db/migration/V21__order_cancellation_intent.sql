ALTER TABLE orders ADD COLUMN cancel_requested_at DATETIME(3) NULL, ADD COLUMN cancel_queue_at DATETIME(3) NULL;
CREATE INDEX idx_orders_cancel_queue ON orders (status, cancel_queue_at, id);
