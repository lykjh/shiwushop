CREATE INDEX idx_orders_buyer_cursor ON orders (user_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_shop_cursor ON orders (shop_id, created_at DESC, id DESC);
