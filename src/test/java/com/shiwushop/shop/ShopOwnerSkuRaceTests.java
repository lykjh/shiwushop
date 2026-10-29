package com.shiwushop.shop;

import com.shiwushop.catalog.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.service.ProductService;
import com.shiwushop.catalog.service.ShopService;
import com.shiwushop.account.service.UserService;
import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.test.TestRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class ShopOwnerSkuRaceTests {
    @Autowired private CaptchaProofStore proofs;
    @Autowired private UserService users;
    @Autowired private ShopService shops;
    @Autowired private ProductService products;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categories;
    @Autowired private ProductMapper productMapper;
    @MockitoSpyBean private SkuMapper skus;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void transferWaitsForAlreadyAuthorizedSkuUpdate() throws Exception {
        Long oldOwner = user("MERCHANT");
        Long newOwner = user("USER");
        Shop shop = new Shop();
        shop.setName("青禾家居店" + oldOwner);
        shop.setOwnerUserId(oldOwner);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Category category = new Category();
        category.setName("家居收纳" + oldOwner);
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categories.insert(category);
        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("桌面收纳盒");
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setOptionSignature("a".repeat(64));
        sku.setPrice(new BigDecimal("10.00"));
        sku.setStock(3);
        skus.insert(sku);

        CountDownLatch checkedOwner = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(call -> {
            checkedOwner.countDown();
            assertTrue(proceed.await(10, TimeUnit.SECONDS));
            return sku;
        }).when(skus).selectForUpdate(sku.getId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            MerchantSkuUpdateDTO update = new MerchantSkuUpdateDTO();
            update.setPrice(new BigDecimal("12.00"));
            update.setStock(4);
            var pending = workers.submit(() -> products.updateMerchantSku(oldOwner, product.getId(), sku.getId(), update));
            assertTrue(checkedOwner.await(10, TimeUnit.SECONDS));
            CountDownLatch transferStarted = new CountDownLatch(1);
            var transfer = workers.submit(() -> {
                transferStarted.countDown();
                return shops.changeOwner(shop.getId(), newOwner);
            });
            assertTrue(transferStarted.await(10, TimeUnit.SECONDS));
            proceed.countDown();
            pending.get(10, TimeUnit.SECONDS);
            transfer.get(10, TimeUnit.SECONDS);
            assertEquals(newOwner, shopMapper.selectById(shop.getId()).getOwnerUserId());
            assertEquals(new BigDecimal("12.00"), skus.selectById(sku.getId()).getPrice());
        } finally {
            proceed.countDown();
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='PRODUCT' AND aggregate_id=?", product.getId());
            jdbc.update("DELETE FROM inventory_movements WHERE sku_id=?", sku.getId());
            jdbc.update("DELETE FROM skus WHERE id=?", sku.getId());
            jdbc.update("DELETE FROM products WHERE id=?", product.getId());
            jdbc.update("DELETE FROM shops WHERE id=?", shop.getId());
            jdbc.update("DELETE FROM categories WHERE id=?", category.getId());
            jdbc.update("DELETE FROM user_accounts WHERE user_id IN (?,?)", oldOwner, newOwner);
            jdbc.update("DELETE FROM users WHERE id IN (?,?)", oldOwner, newOwner);
        }
    }

    private Long user(String role) {
        String name = "owner_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(name, "Shop123!", null));
        jdbc.update("UPDATE user_accounts SET role=? WHERE username=?", role, name);
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, name);
    }
}
