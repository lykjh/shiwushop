ALTER TABLE product_reviews
    ADD COLUMN counted_in_summary TINYINT NOT NULL DEFAULT 0;

CREATE TABLE product_review_summaries (
    product_id BIGINT NOT NULL PRIMARY KEY,
    review_count BIGINT NOT NULL DEFAULT 0,
    rating_sum BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_review_summaries_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_review_summaries_count CHECK (review_count >= 0),
    CONSTRAINT ck_review_summaries_sum CHECK (rating_sum >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Backfill only reviews that are visible and still have unrefunded quantity.
UPDATE product_reviews review
JOIN order_items item ON item.id = review.order_item_id
SET review.counted_in_summary = 1
WHERE review.deleted_at IS NULL AND review.hidden_at IS NULL
  AND item.quantity > (
      SELECT COALESCE(SUM(refunded.quantity), 0)
      FROM after_sale_items refunded
      JOIN after_sale_cases sale ON sale.id = refunded.case_id
      WHERE refunded.order_item_id = item.id AND sale.status = 'REFUNDED'
  );

INSERT INTO product_review_summaries (product_id, review_count, rating_sum)
SELECT product_id, COUNT(*), SUM(rating)
FROM product_reviews WHERE counted_in_summary = 1
GROUP BY product_id;
