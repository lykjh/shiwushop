package com.shiwushop.integration.component.product;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.entity.category.Category;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.product.mapper.category.CategoryMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class TradingProductCurrentReadTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ShopMapper shops;
    @Autowired ShopService shopService;
    @Autowired CategoryMapper categories;
    @Autowired ProductMapper products;
    @Autowired TradingProductService trading;

    @Test
    void rejectsProductStoppedAfterRepeatableReadSnapshot() {
        try (TestAccounts accounts = new TestAccounts(jdbc)) {
            long ownerId = accounts.create("MERCHANT", "Shop123!").id();
            Shop shop = new Shop();
            shop.setOwnerUserId(ownerId);
            shop.setName("当前读店铺-" + UUID.randomUUID());
            shop.setEnabled(1);
            shops.insert(shop);
            Category category = new Category();
            category.setName("当前读分类-" + UUID.randomUUID());
            category.setLevel(1);
            category.setVisible(1);
            category.setSortOrder(0);
            categories.insert(category);
            Product product = new Product();
            product.setShopId(shop.getId());
            product.setCategoryId(category.getId());
            product.setName("当前读商品-" + UUID.randomUUID());
            product.setStatus(ProductStatus.ON_SALE);
            products.insert(product);
            try {
                TransactionTemplate outer = new TransactionTemplate(transactions);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                TransactionTemplate stateChange = new TransactionTemplate(transactions);
                stateChange.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                outer.executeWithoutResult(ignored -> {
                    assertEquals("ON_SALE", jdbc.queryForObject(
                            "SELECT status FROM products WHERE id=?", String.class, product.getId()));
                    stateChange.executeWithoutResult(inner -> {
                        shopService.lockById(shop.getId());
                        products.selectForUpdate(product.getId());
                        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?", product.getId());
                    });
                    assertEquals("ON_SALE", jdbc.queryForObject(
                            "SELECT status FROM products WHERE id=?", String.class, product.getId()));
                    shopService.lockById(shop.getId());
                    BusinessException error = assertThrows(BusinessException.class,
                            () -> trading.requireMarketableProductForUpdate(shop.getId(), product.getId()));
                    assertEquals(409, error.getCode());
                    ignored.setRollbackOnly();
                });
                assertEquals("OFF_SALE", jdbc.queryForObject(
                        "SELECT status FROM products WHERE id=?", String.class, product.getId()));
            } finally {
                products.deleteById(product.getId());
                categories.deleteById(category.getId());
                shops.deleteById(shop.getId());
            }
        }
    }
}
