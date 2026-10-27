package com.shiwushop.cart;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.address.Address;
import com.shiwushop.entity.cart.CartItem;
import com.shiwushop.entity.category.Category;
import com.shiwushop.entity.product.Product;
import com.shiwushop.entity.product.Sku;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.address.AddressMapper;
import com.shiwushop.mapper.cart.CartItemMapper;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.product.ProductMapper;
import com.shiwushop.mapper.product.SkuMapper;
import com.shiwushop.mapper.shop.ShopMapper;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.entity.user.UserProfile;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.dto.cart.CheckoutDTO;
import com.shiwushop.service.CartService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class CheckoutTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private PrivacyCipher privacyCipher;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private AddressMapper addressMapper;
    @Autowired private CartItemMapper cartItemMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CartService cartService;
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void checkoutAcceptsOneHundredDistinctItems() throws Exception {
        Fixture fixture = fixture();
        List<Long> ids = new java.util.ArrayList<>();
        ids.add(item(fixture, fixture.firstSku, 1).getId());
        for (int n = 1; n < CheckoutDTO.MAX_CART_ITEMS; n++) {
            Sku sku = new Sku();
            sku.setProductId(fixture.firstSku.getProductId());
            sku.setOptionSignature(UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", ""));
            sku.setPrice(BigDecimal.TEN);
            sku.setStock(1);
            skuMapper.insert(sku);
            ids.add(item(fixture, sku, 1).getId());
        }

        long start = System.nanoTime();
        mockMvc.perform(post("/checkouts").session(fixture.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(ids, fixture.address.getId(), "1000.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(1));
        System.out.println("C04 100-item checkout elapsed_ms="
                + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM order_items item "
                + "JOIN orders order_head ON order_head.id=item.order_id WHERE order_head.user_id=?",
                Integer.class, fixture.buyer.getId()));
    }

    @Test
    void checkoutRejectsMoreThanOneHundredItemsBeforeCreatingARequest() throws Exception {
        Fixture fixture = fixture();
        List<Long> ids = LongStream.rangeClosed(1, 101).boxed().toList();
        int requestsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?",
                Integer.class, fixture.buyer.getId());
        mockMvc.perform(post("/checkouts").session(fixture.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(ids, fixture.address.getId(), "10.00")))
                .andExpect(status().isBadRequest());

        CheckoutDTO directCall = new CheckoutDTO();
        directCall.setCartItemIds(ids);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> cartService.checkoutSelected(fixture.buyer.getId(), directCall)).getCode());
        assertEquals(requestsBefore, jdbc.queryForObject(
                "SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, fixture.buyer.getId()));
    }

    @Test
    void oldOrderCreationRoutesAreGone() throws Exception {
        mockMvc.perform(post("/orders"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/cart/items/1/checkout"))
                .andExpect(status().isNotFound());
    }

    @Test
    void splitsByShopAndWritesSnapshots() throws Exception {
        Fixture f = fixture();
        CartItem first = item(f, f.firstSku, 2);
        CartItem second = item(f, f.secondSku, 1);
        CartItem third = item(f, f.thirdSku, 1);
        String key = UUID.randomUUID().toString();
        String body = body(List.of(first.getId(), second.getId(), third.getId()), f.address.getId(), "32.00", key);

        mockMvc.perform(post("/checkouts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        MvcResult response = mockMvc.perform(post("/checkouts").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(2))
                .andExpect(jsonPath("$.data.orders[0].shopId").value(f.firstShop.getId()))
                .andExpect(jsonPath("$.data.orders[1].shopId").value(f.secondShop.getId()))
                .andExpect(jsonPath("$.data.orders[0].totalAmount").value(27.00))
                .andExpect(jsonPath("$.data.totalAmount").value(32.00))
                .andReturn();
        long firstOrderId = om.readTree(response.getResponse().getContentAsString())
                .path("data").path("orders").get(0).path("id").asLong();
        mockMvc.perform(get("/orders/{id}", firstOrderId).session(f.session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(f.firstShop.getId()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(20.00));

        assertNull(cartItemMapper.selectById(first.getId()));
        assertNull(cartItemMapper.selectById(second.getId()));
        assertNull(cartItemMapper.selectById(third.getId()));
        assertEquals(1, skuMapper.selectById(f.firstSku.getId()).getStock());
        assertEquals(2, skuMapper.selectById(f.secondSku.getId()).getStock());
        Integer lines = jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE sku_id IN (?, ?, ?)",
                Integer.class, f.firstSku.getId(), f.secondSku.getId(), f.thirdSku.getId());
        assertEquals(3, lines);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id IN (?, ?, ?)",
                Integer.class, f.firstSku.getId(), f.secondSku.getId(), f.thirdSku.getId()));
        assertNotNull(jdbc.queryForObject("SELECT last_used_at FROM addresses WHERE id = ?",
                java.time.LocalDateTime.class, f.address.getId()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id IN (?, ?, ?)",
                Integer.class, f.firstSku.getProductId(), f.secondSku.getProductId(), f.thirdSku.getProductId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_request_orders WHERE checkout_request_id = (SELECT id FROM checkout_requests WHERE user_id = ? AND idempotency_key = ?)",
                Integer.class, f.buyer.getId(), key));
        assertEquals("张三", privacyCipher.decrypt(f.buyer.getId(), "orders:consignee", 1,
                orderMapper.selectById(firstOrderId).getConsigneeCiphertext()));
        jdbc.update("UPDATE addresses SET province=?, detail_ciphertext=? WHERE id=?", "北京市",
                privacyCipher.encrypt(f.buyer.getId(), "detail", "新收货地址"), f.address.getId());
        assertEquals("上海市", orderMapper.selectById(firstOrderId).getProvince());
        assertEquals("测试收货地址", privacyCipher.decrypt(f.buyer.getId(), "orders:detail", 1,
                orderMapper.selectById(firstOrderId).getDetailCiphertext()));
        MvcResult retry = mockMvc.perform(post("/checkouts").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(firstOrderId, om.readTree(retry.getResponse().getContentAsString())
                .path("data").path("orders").get(0).path("id").asLong());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id IN (?, ?, ?)",
                Integer.class, f.firstSku.getProductId(), f.secondSku.getProductId(), f.thirdSku.getProductId()));
        mockMvc.perform(post("/checkouts").session(f.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(first.getId()), f.address.getId(), "10.00", key)))
                .andExpect(status().isConflict());
    }

    @Test
    void mismatchAndUnavailableItemLeaveEverythingUntouched() throws Exception {
        Fixture f = fixture();
        CartItem first = item(f, f.firstSku, 2);
        CartItem second = item(f, f.secondSku, 1);
        int ordersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);

        mockMvc.perform(post("/checkouts").session(f.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(first.getId(), second.getId()), f.address.getId(), "24.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.data.latestTotal").value(25.00));
        assertEquals(3, skuMapper.selectById(f.firstSku.getId()).getStock());
        assertNotNull(cartItemMapper.selectById(first.getId()));

        f.secondSku.setStock(0);
        skuMapper.updateById(f.secondSku);
        mockMvc.perform(post("/checkouts").session(f.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(first.getId(), second.getId()), f.address.getId(), "25.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.skuId").value(f.secondSku.getId()));
        assertEquals(ordersBefore, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(3, skuMapper.selectById(f.firstSku.getId()).getStock());
        assertNotNull(cartItemMapper.selectById(first.getId()));
        assertNotNull(cartItemMapper.selectById(second.getId()));
        assertNull(jdbc.queryForObject("SELECT last_used_at FROM addresses WHERE id = ?",
                java.time.LocalDateTime.class, f.address.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id IN (?, ?)",
                Integer.class, f.firstSku.getProductId(), f.secondSku.getProductId()));
    }


    @Test
    void rejectsOtherUsersItemsAndDuplicateIds() throws Exception {
        Fixture f = fixture();
        CartItem first = item(f, f.firstSku, 1);
        UserProfile other = user();
        MockHttpSession otherSession = new MockHttpSession();
        otherSession.setAttribute("userId", other.getId());
        mockMvc.perform(post("/checkouts").session(otherSession).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(first.getId()), f.address.getId(), "10.00")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/checkouts").session(f.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(first.getId(), first.getId()), f.address.getId(), "20.00")))
                .andExpect(status().isBadRequest());
        assertNotNull(cartItemMapper.selectById(first.getId()));
    }

    @Test
    void directPurchaseWritesOneOrderWithoutChangingCart() throws Exception {
        Fixture f = fixture();
        CartItem saved = item(f, f.firstSku, 1);
        String body = directBody(f.firstSku.getId(), 2, f.address.getId(), "20.00");

        mockMvc.perform(post("/checkouts/direct").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        MvcResult response = mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(1))
                .andExpect(jsonPath("$.data.orders[0].shopId").value(f.firstShop.getId()))
                .andExpect(jsonPath("$.data.totalAmount").value(20.00))
                .andReturn();
        long orderId = om.readTree(response.getResponse().getContentAsString())
                .path("data").path("orders").get(0).path("id").asLong();
        mockMvc.perform(get("/orders/{id}", orderId).session(f.session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].skuId").value(f.firstSku.getId()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
        assertNotNull(cartItemMapper.selectById(saved.getId()));
        assertEquals(1, skuMapper.selectById(f.firstSku.getId()).getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id = ? AND reason = 'ORDER_DEDUCT'",
                Integer.class, f.firstSku.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, f.firstSku.getProductId()));
        MvcResult retry = mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(orderId, om.readTree(retry.getResponse().getContentAsString())
                .path("data").path("orders").get(0).path("id").asLong());
        assertEquals(1, skuMapper.selectById(f.firstSku.getId()).getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, f.firstSku.getProductId()));
    }

    @Test
    void twoSkusOfSameProductCreateOneSearchRefresh() throws Exception {
        Fixture f = fixture();
        Sku other = new Sku();
        other.setProductId(f.firstSku.getProductId());
        other.setOptionSignature(UUID.randomUUID().toString().replace("-", ""));
        other.setPrice(new BigDecimal("10.00"));
        other.setStock(3);
        skuMapper.insert(other);
        CartItem first = item(f, f.firstSku, 1);
        CartItem second = item(f, other, 1);

        mockMvc.perform(post("/checkouts").session(f.session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(first.getId(), second.getId()), f.address.getId(), "20.00")))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, f.firstSku.getProductId()));
    }

    @Test
    void directPurchaseRejectsWrongPriceMissingResourcesAndUnavailableSku() throws Exception {
        Fixture f = fixture();
        int ordersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(f.firstSku.getId(), 1, f.address.getId(), "9.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.data.latestTotal").value(10.00));
        mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(Long.MAX_VALUE, 1, f.address.getId(), "10.00")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(f.firstSku.getId(), 1, Long.MAX_VALUE, "10.00")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(f.firstSku.getId(), 0, f.address.getId(), "10.00")))
                .andExpect(status().isBadRequest());
        f.firstSku.setStock(0);
        skuMapper.updateById(f.firstSku);
        mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(f.firstSku.getId(), 1, f.address.getId(), "10.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("OUT_OF_STOCK"));
        f.firstSku.setStock(3);
        skuMapper.updateById(f.firstSku);
        f.firstShop.setEnabled(0);
        shopMapper.updateById(f.firstShop);
        mockMvc.perform(post("/checkouts/direct").session(f.session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody(f.firstSku.getId(), 1, f.address.getId(), "10.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("UNAVAILABLE"));
        assertEquals(ordersBefore, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    private String body(List<Long> ids, Long addressId, String total) {
        return body(ids, addressId, total, UUID.randomUUID().toString());
    }

    private String body(List<Long> ids, Long addressId, String total, String key) {
        return om.writeValueAsString(Map.of("cartItemIds", ids, "addressId", addressId,
                "confirmedTotal", new BigDecimal(total), "idempotencyKey", key));
    }

    private String directBody(Long skuId, int quantity, Long addressId, String total) {
        return om.writeValueAsString(Map.of("skuId", skuId, "quantity", quantity,
                "addressId", addressId, "confirmedTotal", new BigDecimal(total),
                "idempotencyKey", UUID.randomUUID().toString()));
    }

    private CartItem item(Fixture f, Sku sku, int quantity) {
        CartItem item = new CartItem();
        item.setUserId(f.buyer.getId());
        item.setSkuId(sku.getId());
        item.setQuantity(quantity);
        cartItemMapper.insert(item);
        return item;
    }

    private Fixture fixture() {
        UserProfile buyer = user();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", buyer.getId());
        Address address = new Address();
        address.setUserId(buyer.getId());
        address.setConsigneeCiphertext(privacyCipher.encrypt(buyer.getId(), "consignee", "张三"));
        address.setPhoneCiphertext(privacyCipher.encrypt(buyer.getId(), "phone", "13800138000"));
        address.setProvince("上海市");
        address.setCity("上海市");
        address.setDistrict("浦东新区");
        address.setDetailCiphertext(privacyCipher.encrypt(buyer.getId(), "detail", "测试收货地址"));
        address.setKeyVersion(1);
        address.setIsDefault(1);
        addressMapper.insert(address);
        Category category = new Category();
        category.setName("结算分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Shop firstShop = shop(buyer);
        Shop secondShop = shop(buyer);
        Sku firstSku = sku(category, firstShop, "10.00");
        Sku secondSku = sku(category, secondShop, "5.00");
        Sku thirdSku = sku(category, firstShop, "7.00");
        return new Fixture(buyer, session, address, firstShop, secondShop, firstSku, secondSku, thirdSku);
    }

    private UserProfile user() {
        String username = "checkout-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        UserProfile user = new UserProfile();
        user.setId(jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username));
        return user;
    }

    private Shop shop(UserProfile owner) {
        Shop shop = new Shop();
        shop.setOwnerUserId(owner.getId());
        shop.setName("结算店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        return shop;
    }

    private Sku sku(Category category, Shop shop, String price) {
        Product product = new Product();
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setName("结算商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.ON_SALE);
        productMapper.insert(product);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        String signature = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(signature + signature);
        sku.setPrice(new BigDecimal(price));
        sku.setStock(3);
        skuMapper.insert(sku);
        return sku;
    }

    private record Fixture(UserProfile buyer, MockHttpSession session, Address address, Shop firstShop,
                           Shop secondShop, Sku firstSku, Sku secondSku, Sku thirdSku) {}
}
