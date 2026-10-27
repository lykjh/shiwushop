package com.shiwushop.review;

import com.shiwushop.mapper.review.ProductReviewMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ReviewSummaryConcurrencyTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductReviewMapper reviews;

    @Test
    void concurrentReviewsOfOneProductDoNotLoseSummaryIncrements() throws Exception {
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (nickname) VALUES (?)", token);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, token);
        jdbc.update("INSERT INTO shops (owner_user_id,name,enabled) VALUES (?,?,1)", userId, token);
        Long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, token);
        jdbc.update("INSERT INTO categories (level,name,visible) VALUES (1,?,1)", token);
        Long categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, token);
        jdbc.update("INSERT INTO products (shop_id,category_id,name,status) VALUES (?,?,?,'ON_SALE')",
                shopId, categoryId, token);
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, token);

        try {
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
                ArrayList<Future<Integer>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    writes.add(workers.submit(() -> {
                        start.await();
                        return reviews.addToSummary(productId, 5);
                    }));
                }
                start.countDown();
                for (Future<Integer> write : writes) write.get();
            }
            assertEquals(8, reviews.selectSummary(productId).reviewCount());
            assertEquals(40, jdbc.queryForObject(
                    "SELECT rating_sum FROM product_review_summaries WHERE product_id=?", Integer.class, productId));
        } finally {
            jdbc.update("DELETE FROM product_review_summaries WHERE product_id=?", productId);
            jdbc.update("DELETE FROM products WHERE id=?", productId);
            jdbc.update("DELETE FROM categories WHERE id=?", categoryId);
            jdbc.update("DELETE FROM shops WHERE id=?", shopId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }
}
