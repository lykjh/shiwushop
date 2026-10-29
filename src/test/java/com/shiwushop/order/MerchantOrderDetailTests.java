package com.shiwushop.order;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.commerce.entity.status.OrderStatus;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.account.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class MerchantOrderDetailTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PrivacyCipher privacyCipher;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private OrderMapper orderMapper;

    @Test
    void ownerCanReadPausedShopsOrderWithPurchaseSnapshots() throws Exception {
        Long owner = user("MERCHANT");
        Shop shop = shop(owner, 0);
        Sku sku = sku(shop);
        Order order = order(shop);
        orderMapper.insertCheckoutItem(order.getId(), sku.getId(), "下单时商品名",
                "白色", 2, new BigDecimal("10.00"));
        orderMapper.insertCheckoutItem(order.getId(), sku.getId(), "下单时商品名",
                "黑色", 1, new BigDecimal("20.00"));
        sku.setPrice(new BigDecimal("99.00"));
        skuMapper.updateById(sku);

        mockMvc.perform(get("/merchant/shops/{shopId}/orders/{orderId}", shop.getId(), order.getId())
                        .session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(order.getId()))
                .andExpect(jsonPath("$.data.shopName").value(shop.getName()))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.addressSnapshot.consignee").value("张三"))
                .andExpect(jsonPath("$.data.addressSnapshot.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.addressSnapshot.address").value("北京市北京市海淀区下单地址"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].productName").value("下单时商品名"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(10.00))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(20.00))
                .andExpect(jsonPath("$.data.items[1].lineAmount").value(20.00))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
    }

    @Test
    void hidesOtherShopsOrdersAndRevokesPreviousOwner() throws Exception {
        Long owner = user("MERCHANT");
        Long otherMerchant = user("MERCHANT");
        Long ordinary = user("USER");
        Shop shop = shop(owner, 1);
        Shop otherShop = shop(otherMerchant, 1);
        Order order = order(shop);
        Order otherOrder = order(otherShop);
        String path = "/merchant/shops/{shopId}/orders/{orderId}";

        mockMvc.perform(get(path, shop.getId(), order.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path, shop.getId(), order.getId()).session(session(otherMerchant)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(path, shop.getId(), otherOrder.getId()).session(session(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(path, shop.getId(), Long.MAX_VALUE).session(session(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(path, shop.getId(), 0).session(session(owner)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(path, 0, order.getId()).session(session(owner)))
                .andExpect(status().isBadRequest());

        Shop ordinaryShop = shop(ordinary, 1);
        mockMvc.perform(get(path, ordinaryShop.getId(), order.getId()).session(session(ordinary)))
                .andExpect(status().isForbidden());

        shop.setOwnerUserId(otherMerchant);
        shopMapper.updateById(shop);
        mockMvc.perform(get(path, shop.getId(), order.getId()).session(session(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(path, shop.getId(), order.getId()).session(session(otherMerchant)))
                .andExpect(status().isOk());
    }

    private Long user(String role) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }

    private Shop shop(Long owner, int enabled) {
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("商家订单详情店铺-" + UUID.randomUUID());
        shop.setEnabled(enabled);
        shopMapper.insert(shop);
        return shop;
    }

    private Sku sku(Shop shop) {
        Category category = new Category();
        category.setName("商家订单详情分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Product product = new Product();
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setName("当前商品名");
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        String hex = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(hex + hex);
        sku.setPrice(new BigDecimal("10.00"));
        sku.setStock(3);
        skuMapper.insert(sku);
        return sku;
    }

    private Order order(Shop shop) {
        Order order = new Order();
        order.setUserId(user("USER"));
        order.setShopId(shop.getId());
        order.setTotalAmount(new BigDecimal("40.00"));
        order.setConsigneeCiphertext(privacyCipher.encrypt(order.getUserId(), "orders:consignee", "张三"));
        order.setPhoneCiphertext(privacyCipher.encrypt(order.getUserId(), "orders:phone", "13800138000"));
        order.setDetailCiphertext(privacyCipher.encrypt(order.getUserId(), "orders:detail", "下单地址"));
        order.setProvince("北京市"); order.setCity("北京市"); order.setDistrict("海淀区");
        order.setShippingKeyVersion(1);
        order.setPayExpiresAt(LocalDateTime.now().plusMinutes(15));
        order.setPaidAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PAID);
        orderMapper.insert(order);
        return order;
    }
}
