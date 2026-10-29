package com.shiwushop.order;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.status.OrderStatus;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class MerchantOrderListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivacyCipher cipher;
    @Autowired private ShopMapper shops;
    @Autowired private OrderMapper orders;
    @Autowired private CategoryMapper categories;
    @Autowired private ProductMapper products;
    @Autowired private SkuMapper skus;

    @Test
    void pagesOwnShopOrdersEvenWhenPaused() throws Exception {
        Long owner = user("MERCHANT"), nextOwner = user("MERCHANT"), buyer = user("USER");
        Shop shop = shop(owner, 0), foreign = shop(nextOwner, 1);
        Order oldest = order(buyer, shop), newest = order(buyer, shop);
        Order foreignOrder = order(buyer, foreign);
        item(oldest, 1); item(oldest, 2); item(newest, 1);
        mvc.perform(get("/merchant/shops/{id}/orders?size=1", shop.getId()).session(session(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].id").value(newest.getId()))
                .andExpect(jsonPath("$.data.items[0].itemCount").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(newest.getId()));
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId())
                        .param("afterId", newest.getId().toString()).param("size", "1").session(session(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(oldest.getId()))
                .andExpect(jsonPath("$.data.items[0].itemCount").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId())
                        .param("afterId", foreignOrder.getId().toString()).session(session(owner)))
                .andExpect(status().isBadRequest());
        shop.setOwnerUserId(nextOwner); shops.updateById(shop);
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId()).session(session(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId()).session(session(nextOwner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void requiresActiveMerchantOwnerAndValidPage() throws Exception {
        Long ordinary = user("USER"), merchant = user("MERCHANT");
        Shop shop = shop(ordinary, 1);
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId()).session(session(ordinary)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId()).session(session(merchant)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/merchant/shops/{id}/orders?page=0", shop.getId()).session(session(ordinary)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/merchant/shops/{id}/orders?includeTotal=true", shop.getId()).session(session(ordinary)))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", ordinary);
        mvc.perform(get("/merchant/shops/{id}/orders", shop.getId()).session(session(ordinary)))
                .andExpect(status().isForbidden());
    }

    private Long user(String role) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbc.update("UPDATE user_accounts SET role=? WHERE username=?", role, username);
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
    }
    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession(); session.setAttribute("userId", userId); return session;
    }
    private Shop shop(Long owner, int enabled) {
        Shop s = new Shop(); s.setOwnerUserId(owner); s.setName("店铺-" + UUID.randomUUID());
        s.setEnabled(enabled); shops.insert(s); return s;
    }
    private Order order(Long buyer, Shop shop) {
        Order o = new Order(); o.setUserId(buyer); o.setShopId(shop.getId());
        o.setTotalAmount(new BigDecimal("10.00")); o.setStatus(OrderStatus.UNPAID);
        o.setPayExpiresAt(LocalDateTime.now().plusMinutes(15));
        o.setConsigneeCiphertext(cipher.encrypt(buyer, "orders:consignee", "张三"));
        o.setPhoneCiphertext(cipher.encrypt(buyer, "orders:phone", "13800138000"));
        o.setDetailCiphertext(cipher.encrypt(buyer, "orders:detail", "测试地址"));
        o.setProvince("北京市"); o.setCity("北京市"); o.setDistrict("海淀区");
        o.setShippingKeyVersion(1); orders.insert(o); return o;
    }
    private void item(Order order, int index) {
        Category category = new Category(); category.setName("分类-" + UUID.randomUUID());
        category.setLevel(1); category.setVisible(1); category.setSortOrder(0); categories.insert(category);
        Product product = new Product(); product.setShopId(order.getShopId()); product.setCategoryId(category.getId());
        product.setName("商品-" + UUID.randomUUID()); product.setStatus(ProductStatus.DRAFT); products.insert(product);
        Sku sku = new Sku(); sku.setProductId(product.getId());
        String hex = UUID.randomUUID().toString().replace("-", ""); sku.setOptionSignature(hex + hex);
        sku.setPrice(new BigDecimal("10.00")); sku.setStock(1); skus.insert(sku);
        jdbc.update("INSERT INTO order_items(order_id,sku_id,product_name,sku_spec,quantity,unit_price) " +
                "VALUES (?,?,?,?,1,10.00)", order.getId(), sku.getId(), "商品", "规格" + index);
    }
}
