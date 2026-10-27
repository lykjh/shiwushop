package com.shiwushop.order;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.category.Category;
import com.shiwushop.entity.order.Order;
import com.shiwushop.entity.product.Product;
import com.shiwushop.entity.product.Sku;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.product.ProductMapper;
import com.shiwushop.mapper.product.SkuMapper;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.mapper.user.UserAccountMapper;

import com.shiwushop.entity.status.OrderStatus;
import com.shiwushop.entity.status.AccountStatus;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.entity.user.UserAccount;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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
class OrderDetailTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivacyCipher cipher;
    @Autowired private UserAccountMapper accountMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private StringRedisTemplate redis;

    @Test
    void returnsEncryptedAddressAndSaleSnapshotOnlyToBuyer() throws Exception {
        Long buyer = user();
        Long other = user();
        Shop shop = new Shop();
        shop.setOwnerUserId(buyer);
        shop.setName("订单详情店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Category category = new Category();
        category.setName("订单详情分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("当前商品名");
        product.setStatus(ProductStatus.ON_SALE);
        productMapper.insert(product);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        String signature = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(signature + signature);
        sku.setPrice(new BigDecimal("99.00"));
        sku.setStock(3);
        skuMapper.insert(sku);
        Order order = new Order();
        order.setUserId(buyer);
        order.setShopId(shop.getId());
        order.setTotalAmount(new BigDecimal("20.00"));
        order.setStatus(OrderStatus.UNPAID);
        order.setPayExpiresAt(LocalDateTime.now().plusMinutes(15));
        order.setConsigneeCiphertext(cipher.encrypt(buyer, "orders:consignee", "张三"));
        order.setPhoneCiphertext(cipher.encrypt(buyer, "orders:phone", "13800138000"));
        order.setProvince("上海市");
        order.setCity("上海市");
        order.setDistrict("浦东新区");
        order.setDetailCiphertext(cipher.encrypt(buyer, "orders:detail", "旧地址"));
        order.setShippingKeyVersion(1);
        orderMapper.insert(order);
        orderMapper.insertCheckoutItem(order.getId(), sku.getId(), "成交商品名", "白色", 2,
                new BigDecimal("10.00"));
        Long orderItemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order.getId());
        MockHttpSession own = session(buyer);

        mockMvc.perform(get("/orders/{id}", order.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/orders/{id}", order.getId()).session(session(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/{id}", order.getId()).session(own))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopName").value(shop.getName()))
                .andExpect(jsonPath("$.data.addressSnapshot.consignee").value("张三"))
                .andExpect(jsonPath("$.data.addressSnapshot.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.addressSnapshot.address").value("上海市上海市浦东新区旧地址"))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(orderItemId))
                .andExpect(jsonPath("$.data.items[0].productName").value("成交商品名"))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(20.00))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        UserAccount account = accountMapper.selectById(buyer);
        account.setStatus(AccountStatus.DISABLED);
        accountMapper.updateById(account);
        // Fixture SQL bypasses the service's cache invalidation.
        redis.delete("shiwushop:auth:state:" + buyer);
        mockMvc.perform(get("/orders/{id}", order.getId()).session(own))
                .andExpect(status().isUnauthorized());
    }

    private Long user() {
        String username = "order-detail-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
