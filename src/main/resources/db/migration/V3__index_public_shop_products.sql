CREATE INDEX idx_products_shop_status_category
    ON products (shop_id, status, category_id);
