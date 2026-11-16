package com.shiwushop.integration.component.product;

import com.shiwushop.common.BusinessException;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MarketingStockMovementMysqlTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductStockService stock;

    @Test
    void allocatesReplaysAndReleasesOnlyNormalStock() {
        try (var accounts = new TestAccounts(jdbc)) {
            long actor = accounts.create("MERCHANT", "Shop123!").id();
            String suffix = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", actor, "划拨店铺-" + suffix);
            long shop = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, "划拨店铺-" + suffix);
            jdbc.update("INSERT INTO categories(name,level,visible,sort_order) VALUES (?,1,1,0)", "划拨分类-" + suffix);
            long category = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class,
                    "划拨分类-" + suffix);
            jdbc.update("INSERT INTO products(shop_id,category_id,name,status) VALUES (?,?,?,'ON_SALE')",
                    shop, category, "划拨商品-" + suffix);
            long product = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class,
                    "划拨商品-" + suffix);
            jdbc.update("INSERT INTO skus(product_id,option_signature,price,stock) VALUES (?,REPEAT('b',64),19.00,5)",
                    product);
            long sku = jdbc.queryForObject("SELECT id FROM skus WHERE product_id=?", Long.class, product);
            jdbc.update("""
                    INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
                      eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash)
                    VALUES (?,1,'划拨验证','FLASH','DRAFT',NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY,
                      JSON_ARRAY('ORDINARY'),?,'验证规则','CREATE_MERCHANT',?,UNHEX(SHA2(?,256)))
                    """, suffix, actor, suffix, suffix);
            long activity = jdbc.queryForObject("SELECT id FROM marketing_activities WHERE activity_code=?",
                    Long.class, suffix);
            jdbc.update("INSERT INTO marketing_activity_skus(activity_id,sku_id) VALUES (?,?)", activity, sku);
            long activitySku = jdbc.queryForObject("SELECT id FROM marketing_activity_skus WHERE activity_id=?",
                    Long.class, activity);
            try {
                var tx = new TransactionTemplate(transactions);
                String key = "normal-" + suffix;
                var first = tx.execute(ignored -> stock.moveNormalForMarketing(actor, sku, activitySku,
                        -3, key, "MARKETING_ALLOCATE"));
                assertEquals(2, first.normalBalanceAfter());
                assertFalse(first.replayed());
                var replay = tx.execute(ignored -> stock.moveNormalForMarketing(actor, sku, activitySku,
                        -3, key, "MARKETING_ALLOCATE"));
                assertEquals(first.movementId(), replay.movementId());
                assertEquals(2, replay.normalBalanceAfter());
                assertTrue(replay.replayed());
                assertEquals(409, assertThrows(BusinessException.class, () -> tx.execute(ignored ->
                        stock.moveNormalForMarketing(actor, sku, activitySku, -1, key,
                                "MARKETING_ALLOCATE"))).getCode());
                var released = tx.execute(ignored -> stock.moveNormalForMarketing(actor, sku, activitySku,
                        2, "release-" + suffix, "MARKETING_RELEASE"));
                assertEquals(4, released.normalBalanceAfter());
                assertEquals(4, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, sku));
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?",
                        Integer.class, sku));
            } finally {
                jdbc.update("DELETE FROM inventory_movements WHERE sku_id=?", sku);
                jdbc.update("DELETE FROM marketing_activity_skus WHERE id=?", activitySku);
                jdbc.update("DELETE FROM marketing_activities WHERE id=?", activity);
                jdbc.update("DELETE FROM skus WHERE id=?", sku);
                jdbc.update("DELETE FROM products WHERE id=?", product);
                jdbc.update("DELETE FROM shops WHERE id=?", shop);
                jdbc.update("DELETE FROM categories WHERE id=?", category);
            }
        }
    }

}
