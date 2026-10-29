package com.shiwushop.cart;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.entity.address.Address;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.entity.user.UserProfile;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.service.UserService;
import com.shiwushop.mapper.address.AddressMapper;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.product.InventoryMovementMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class CheckoutConcurrencyTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserService userService;
    @Autowired private PrivacyCipher cipher;
    @Autowired private AddressMapper addressMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @MockitoSpyBean private InventoryMovementMapper movementMapper;

    private Long firstUserId;
    private Long secondUserId;
    private Long parentCategoryId;
    private Long categoryId;
    private Long shopId;
    private Long productId;
    private Long skuId;
    private Long secondShopId;
    private Long secondProductId;
    private Long secondSkuId;

    @Test
    void onlyOneBuyerGetsTheLastItem() throws Exception {
        UserProfile first = user();
        firstUserId = first.getId();
        UserProfile second = user();
        secondUserId = second.getId();
        Address firstAddress = address(first);
        Address secondAddress = address(second);

        Category parent = new Category();
        parent.setName("并发回归父分类-" + UUID.randomUUID());
        parent.setLevel(1);
        parent.setVisible(1);
        parent.setSortOrder(0);
        categoryMapper.insert(parent);
        parentCategoryId = parent.getId();

        Category category = new Category();
        category.setParentId(parentCategoryId);
        category.setName("并发回归子分类-" + UUID.randomUUID());
        category.setLevel(2);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        categoryId = category.getId();

        Shop shop = new Shop();
        shop.setOwnerUserId(firstUserId);
        shop.setName("并发回归店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        shopId = shop.getId();

        Product product = new Product();
        product.setShopId(shopId);
        product.setCategoryId(categoryId);
        product.setName("并发回归商品");
        product.setStatus(ProductStatus.ON_SALE);
        productMapper.insert(product);
        productId = product.getId();

        Sku sku = new Sku();
        sku.setProductId(productId);
        String signature = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(signature + signature);
        sku.setPrice(new BigDecimal("7.00"));
        sku.setStock(1);
        skuMapper.insert(sku);
        skuId = sku.getId();

        MockHttpSession firstSession = session(firstUserId);
        MockHttpSession secondSession = session(secondUserId);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(20);
        try {
            List<Future<MvcResult>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                MockHttpSession buyer = i % 2 == 0 ? firstSession : secondSession;
                Long addressId = i % 2 == 0 ? firstAddress.getId() : secondAddress.getId();
                String body = new ObjectMapper().writeValueAsString(Map.of(
                        "skuId", skuId, "quantity", 1, "addressId", addressId,
                        "confirmedTotal", new BigDecimal("7.00"),
                        "idempotencyKey", UUID.randomUUID().toString()));
                requests.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发请求未启动");
                    return mockMvc.perform(post("/checkouts/direct").session(buyer)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "20 个请求应全部就绪");
            start.countDown();

            int success = 0;
            int outOfStock = 0;
            ObjectMapper mapper = new ObjectMapper();
            for (Future<MvcResult> request : requests) {
                MvcResult result = request.get(60, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                if (status == 200) success++;
                if (status == 409) {
                    assertEquals("OUT_OF_STOCK", mapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("reason").asText());
                    outOfStock++;
                }
            }
            assertEquals(1, success, "库存为 1 时只能有一笔购买成功");
            assertEquals(19, outOfStock, "其余请求应明确返回缺货");
            assertEquals(0, skuMapper.selectById(skuId).getStock());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM order_items WHERE sku_id = ?", Integer.class, skuId));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "并发请求应在清理数据前结束");
        }
    }

    @Test
    void secondShopFailureRollsBackBothOrdersStockAndCart() throws Exception {
        UserProfile buyer = user();
        firstUserId = buyer.getId();
        Address address = address(buyer);
        Category parent = new Category();
        parent.setName("回滚父分类-" + UUID.randomUUID());
        parent.setLevel(1);
        parent.setVisible(1);
        parent.setSortOrder(0);
        categoryMapper.insert(parent);
        parentCategoryId = parent.getId();
        Category child = new Category();
        child.setParentId(parentCategoryId);
        child.setName("回滚子分类-" + UUID.randomUUID());
        child.setLevel(2);
        child.setVisible(1);
        child.setSortOrder(0);
        categoryMapper.insert(child);
        categoryId = child.getId();
        long[] skuIds = new long[2];
        for (int i = 0; i < 2; i++) {
            Shop shop = new Shop();
            shop.setOwnerUserId(firstUserId);
            shop.setName("回滚店铺-" + UUID.randomUUID());
            shop.setEnabled(1);
            shopMapper.insert(shop);
            if (i == 0) shopId = shop.getId(); else secondShopId = shop.getId();
            Product product = new Product();
            product.setShopId(shop.getId());
            product.setCategoryId(categoryId);
            product.setName("回滚商品-" + i);
            product.setStatus(ProductStatus.ON_SALE);
            productMapper.insert(product);
            if (i == 0) productId = product.getId(); else secondProductId = product.getId();
            Sku sku = new Sku();
            sku.setProductId(product.getId());
            String signature = UUID.randomUUID().toString().replace("-", "");
            sku.setOptionSignature(signature + signature);
            sku.setPrice(new BigDecimal("7.00"));
            sku.setStock(3);
            skuMapper.insert(sku);
            skuIds[i] = sku.getId();
            if (i == 0) skuId = sku.getId(); else secondSkuId = sku.getId();
            jdbc.update("INSERT INTO cart_items(user_id,sku_id,quantity) VALUES (?,?,1)", firstUserId, sku.getId());
        }
        doAnswer(invocation -> {
            if (secondSkuId.equals(invocation.getArgument(0))) throw new IllegalStateException("模拟第二店铺写入失败");
            return invocation.callRealMethod();
        }).when(movementMapper).insertOrderDeduct(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
        List<Long> cartIds = jdbc.queryForList("SELECT id FROM cart_items WHERE user_id=? ORDER BY sku_id", Long.class, firstUserId);
        String body = new ObjectMapper().writeValueAsString(Map.of("cartItemIds", cartIds,
                "addressId", address.getId(), "confirmedTotal", new BigDecimal("14.00"),
                "idempotencyKey", UUID.randomUUID().toString()));
        MvcResult result = mockMvc.perform(post("/checkouts").session(session(firstUserId))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(500, result.getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, firstUserId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, firstUserId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE user_id=?", Integer.class, firstUserId));
        for (long id : skuIds) {
            assertEquals(3, skuMapper.selectById(id).getStock());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?", Integer.class, id));
        }
    }

    @AfterEach
    void removeTestData() {
        if (firstUserId != null) jdbc.update("DELETE FROM cart_items WHERE user_id = ?", firstUserId);
        if (secondSkuId != null) jdbc.update("DELETE FROM inventory_movements WHERE sku_id = ?", secondSkuId);
        if (skuId != null) jdbc.update("DELETE FROM inventory_movements WHERE sku_id = ?", skuId);
        if (firstUserId != null) jdbc.update("DELETE FROM checkout_request_orders WHERE checkout_request_id IN (SELECT id FROM checkout_requests WHERE user_id = ?)", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM checkout_request_orders WHERE checkout_request_id IN (SELECT id FROM checkout_requests WHERE user_id = ?)", secondUserId);
        if (skuId != null) jdbc.update("DELETE FROM order_items WHERE sku_id = ?", skuId);
        if (secondSkuId != null) jdbc.update("DELETE FROM order_items WHERE sku_id = ?", secondSkuId);
        if (firstUserId != null) jdbc.update("DELETE FROM orders WHERE user_id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM orders WHERE user_id = ?", secondUserId);
        if (firstUserId != null) jdbc.update("DELETE FROM checkout_requests WHERE user_id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM checkout_requests WHERE user_id = ?", secondUserId);
        if (skuId != null) jdbc.update("DELETE FROM skus WHERE id = ?", skuId);
        if (secondSkuId != null) jdbc.update("DELETE FROM skus WHERE id = ?", secondSkuId);
        if (productId != null) jdbc.update("DELETE FROM products WHERE id = ?", productId);
        if (secondProductId != null) jdbc.update("DELETE FROM products WHERE id = ?", secondProductId);
        if (shopId != null) jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        if (secondShopId != null) jdbc.update("DELETE FROM shops WHERE id = ?", secondShopId);
        if (firstUserId != null) jdbc.update("DELETE FROM addresses WHERE user_id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM addresses WHERE user_id = ?", secondUserId);
        if (categoryId != null) jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        if (parentCategoryId != null) jdbc.update("DELETE FROM categories WHERE id = ?", parentCategoryId);
        if (firstUserId != null) jdbc.update("DELETE FROM user_accounts WHERE user_id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM user_accounts WHERE user_id = ?", secondUserId);
        if (firstUserId != null) jdbc.update("DELETE FROM user_private WHERE user_id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM user_private WHERE user_id = ?", secondUserId);
        if (firstUserId != null) jdbc.update("DELETE FROM users WHERE id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM users WHERE id = ?", secondUserId);
    }

    private UserProfile user() {
        String username = "race-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        UserProfile user = new UserProfile();
        user.setId(jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username));
        return user;
    }

    private Address address(UserProfile user) {
        Address address = new Address();
        address.setUserId(user.getId());
        address.setConsigneeCiphertext(cipher.encrypt(user.getId(), "consignee", "并发测试"));
        address.setPhoneCiphertext(cipher.encrypt(user.getId(), "phone", "13800138000"));
        address.setProvince("上海市");
        address.setCity("上海市");
        address.setDistrict("浦东新区");
        address.setDetailCiphertext(cipher.encrypt(user.getId(), "detail", "并发回归测试地址"));
        address.setKeyVersion(1);
        address.setIsDefault(1);
        addressMapper.insert(address);
        return address;
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
