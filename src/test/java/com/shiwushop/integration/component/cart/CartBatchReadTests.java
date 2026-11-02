package com.shiwushop.integration.component.cart;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.trade.entity.cart.CartItem;
import com.shiwushop.product.entity.category.Category;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.trade.mapper.cart.CartItemMapper;
import com.shiwushop.product.mapper.category.CategoryMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.mapper.product.SkuOptionValueMapper;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.trade.service.CartService;
import com.shiwushop.user.service.RegisterService;
import com.shiwushop.support.TestRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class CartBatchReadTests {
    @Autowired private RegisterService users;
    @Autowired private CaptchaProofStore proofs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CartService carts;
    @Autowired private CartItemMapper cartItems;
    @MockitoSpyBean private SkuMapper skus;
    @MockitoSpyBean private ProductMapper products;
    @MockitoSpyBean private ShopMapper shops;
    @MockitoSpyBean private CategoryMapper categories;
    @MockitoSpyBean private SkuOptionValueMapper skuValues;

    @Test
    void oneTwentyAndHundredItemsUseTheSameNumberOfReadQueries() {
        String username = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        Category category = new Category();
        category.setName("批量购物车分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categories.insert(category);
        Shop shop = new Shop();
        shop.setOwnerUserId(userId);
        shop.setName("批量购物车店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shops.insert(shop);
        Product product = new Product();
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setName("批量购物车商品");
        product.setStatus(ProductStatus.ON_SALE);
        products.insert(product);

        int previous = 0;
        for (int count : new int[]{1, 20, 100}) {
            for (int i = previous; i < count; i++) {
                Sku sku = new Sku();
                sku.setProductId(product.getId());
                String signature = UUID.randomUUID().toString().replace("-", "");
                sku.setOptionSignature(signature + signature);
                sku.setPrice(BigDecimal.TEN);
                sku.setStock(2);
                skus.insert(sku);
                CartItem item = new CartItem();
                item.setUserId(userId);
                item.setSkuId(sku.getId());
                item.setQuantity(1);
                cartItems.insert(item);
            }
            previous = count;
            clearInvocations(skus, products, shops, categories, skuValues);
            var result = carts.list(userId);
            assertEquals(count, result.size());
            assertTrue(result.stream().allMatch(item -> Boolean.TRUE.equals(item.getAvailable())));
            verify(skus, times(1)).selectBatchIds(anyCollection());
            verify(products, times(1)).selectBatchIds(anyCollection());
            verify(shops, times(1)).selectBatchIds(anyCollection());
            verify(categories, times(1)).selectBatchIds(anyCollection());
            verify(skuValues, times(1)).selectSkuValues(org.mockito.ArgumentMatchers.anyList());
        }
    }
}
