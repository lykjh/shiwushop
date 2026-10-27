-- 店铺上架商品按 ID 游标倒序翻页；分类索引的 category_id 位于 ID 前，无法顺序取页。
CREATE INDEX idx_products_shop_status_id ON products (shop_id, status, id);
