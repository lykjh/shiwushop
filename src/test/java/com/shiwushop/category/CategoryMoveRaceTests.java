package com.shiwushop.category;

import com.shiwushop.catalog.dto.category.CategoryUpdateDTO;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CategoryMoveRaceTests {
    @Autowired private CategoryMapper categories;
    @Autowired private CategoryService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void simultaneousReciprocalMovesNeverPersistCycle() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int run = 0; run < 20; run++) {
                Category a = root();
                Category b = root();
                CountDownLatch start = new CountDownLatch(1);
                try {
                    var first = workers.submit(() -> move(a, b, start));
                    var second = workers.submit(() -> move(b, a, start));
                    start.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    second.get(10, TimeUnit.SECONDS);
                    Long aParent = jdbc.queryForObject("SELECT parent_id FROM categories WHERE id=?", Long.class, a.getId());
                    Long bParent = jdbc.queryForObject("SELECT parent_id FROM categories WHERE id=?", Long.class, b.getId());
                    assertFalse(b.getId().equals(aParent) && a.getId().equals(bParent), "run=" + run);
                } finally {
                    jdbc.update("UPDATE categories SET parent_id=NULL, level=1 WHERE id IN (?,?)", a.getId(), b.getId());
                    jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='CATEGORY' AND aggregate_id IN (?,?)", a.getId(), b.getId());
                    jdbc.update("DELETE FROM categories WHERE id IN (?,?)", a.getId(), b.getId());
                }
            }
        }
    }

    private Category root() {
        Category category = new Category();
        category.setName("并发分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categories.insert(category);
        return category;
    }

    private void move(Category category, Category parent, CountDownLatch start) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            CategoryUpdateDTO update = new CategoryUpdateDTO();
            update.setName(category.getName());
            update.setParentId(parent.getId());
            update.setVisible(1);
            update.setSortOrder(0);
            service.update(category.getId(), update);
        } catch (RuntimeException conflict) {
            // A rejected concurrent move leaves the category tree unchanged.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
