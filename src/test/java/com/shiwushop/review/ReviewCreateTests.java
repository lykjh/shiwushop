package com.shiwushop.review;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.review.ProductReview;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.media.CatalogMediaReferenceMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.review.ProductReviewMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.account.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.media.root=./target/review-media-tests"
})
@AutoConfigureMockMvc
@Transactional
class ReviewCreateTests {
    private final List<Path> mediaFiles = new ArrayList<>();
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivacyCipher cipher;
    @Autowired private CategoryMapper categories;
    @Autowired private CatalogMediaReferenceMapper mediaAssets;
    @Autowired private ShopMapper shops;
    @Autowired private ProductMapper products;
    @Autowired private ProductReviewMapper reviews;
    @Autowired private SkuMapper skus;
    @Autowired private OrderMapper orders;

    @Test
    void onlyBuyerMayReviewConfirmedNonRefundedItemOnce() throws Exception {
        Long buyer = user();
        Long other = user();
        Shop shop = new Shop();
        shop.setOwnerUserId(other);
        shop.setName("评价店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shops.insert(shop);
        Category category = new Category();
        category.setName("评价分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categories.insert(category);
        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("待评价商品");
        product.setStatus(ProductStatus.ON_SALE);
        products.insert(product);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        String signature = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(signature + signature);
        sku.setPrice(BigDecimal.TEN);
        sku.setStock(1);
        skus.insert(sku);
        Order order = new Order();
        order.setUserId(buyer);
        order.setShopId(shop.getId());
        order.setTotalAmount(BigDecimal.TEN);
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        order.setPayExpiresAt(LocalDateTime.now().plusMinutes(15));
        order.setConsigneeCiphertext(cipher.encrypt(buyer, "orders:consignee", "测试"));
        order.setPhoneCiphertext(cipher.encrypt(buyer, "orders:phone", "13800138000"));
        order.setProvince("北京");
        order.setCity("北京");
        order.setDistrict("海淀");
        order.setDetailCiphertext(cipher.encrypt(buyer, "orders:detail", "测试路"));
        order.setShippingKeyVersion(1);
        orders.insert(order);
        orders.insertCheckoutItem(order.getId(), sku.getId(), "成交商品", "", 1, BigDecimal.TEN);
        Long itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order.getId());
        String path = "/orders/{orderId}/items/{itemId}/review";
        Long mediaId = media(buyer);
        String body = "{\"rating\":5,\"content\":\"实际使用很好\",\"imageMediaIds\":[" + mediaId + "]}";

        mvc.perform(post(path, order.getId(), itemId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        jdbc.update("INSERT INTO shipments (order_id, carrier_name, tracking_no, status, shipped_by_user_id, "
                        + "shipped_at, auto_confirm_at, confirmed_at, confirm_source) "
                        + "VALUES (?, '测试快递', 'REVIEW-1', 'CONFIRMED', ?, NOW(), NOW(), NOW(), 'BUYER')",
                order.getId(), other);
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM shipments WHERE order_id=?", String.class, order.getId()));
        assertEquals("PAID", reviews.selectEligibilityForUpdate(itemId).getOrderStatus());
        assertEquals("CONFIRMED", reviews.selectEligibilityForUpdate(itemId).getShipmentStatus());
        mvc.perform(post(path, order.getId(), itemId).with(csrf()).session(session(other))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        var created = mvc.perform(post(path, order.getId(), itemId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data.id").isNumber()).andReturn();
        assertEquals(200, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        assertEquals(true, mediaAssets.isPubliclyReferenced(mediaId));
        String mediaEtag = mvc.perform(get("/media/{id}", mediaId))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        Long reviewId = reviews.selectIdByOrderItemId(itemId);
        mvc.perform(get("/products/{productId}/review-summary", product.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewCount").value(1));
        assertEquals(0, reviews.selectSummary(product.getId()).averageRating().compareTo(new BigDecimal("5")));
        assertEquals(1, searchEvents(product.getId()));
        Long followMediaId = media(buyer);
        Long foreignMediaId = media(other);
        String followPath = "/reviews/{reviewId}/follow-up";
        String followBody = "{\"content\":\"补充使用感受\",\"imageMediaIds\":[" + followMediaId + "]}";
        mvc.perform(post(followPath, reviewId).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(followBody)).andExpect(status().isUnauthorized());
        mvc.perform(post(followPath, reviewId).with(csrf()).session(session(other))
                .contentType(MediaType.APPLICATION_JSON).content(followBody)).andExpect(status().isNotFound());
        mvc.perform(post(followPath, reviewId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(followPath, reviewId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"补充使用感受\",\"imageMediaIds\":[" + foreignMediaId + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(followPath, reviewId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(followBody)).andExpect(status().isOk());
        mvc.perform(post(followPath, reviewId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(followBody)).andExpect(status().isConflict());
        String listPath = "/products/{productId}/reviews";
        mvc.perform(get(listPath, product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rating").value(5))
                .andExpect(jsonPath("$.data.items[0].imageMediaIds[0]").value(mediaId))
                .andExpect(jsonPath("$.data.items[0].followUp").value("补充使用感受"))
                .andExpect(jsonPath("$.data.items[0].followUpImageMediaIds[0]").value(followMediaId))
                .andExpect(jsonPath("$.data.items[0].buyerUserId").doesNotExist())
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(post(path, order.getId(), itemId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());

        Long admin = user();
        Long secondReporter = user();
        jdbc.update("UPDATE user_accounts SET role='ADMIN' WHERE user_id=?", admin);
        String reportPath = "/reviews/{reviewId}/reports";
        String resolvePath = "/admin/review-reports/{reportId}/resolve";
        mvc.perform(post(reportPath, reviewId).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"不实内容\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(reportPath, reviewId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不实内容\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(reportPath, reviewId).with(csrf()).session(session(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(reportPath, reviewId).with(csrf()).session(session(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不实内容\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(reportPath, reviewId).with(csrf()).session(session(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重复提交\"}"))
                .andExpect(status().isOk());
        Long reportId = jdbc.queryForObject("SELECT id FROM product_review_reports WHERE review_id=? AND reporter_user_id=?",
                Long.class, reviewId, other);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM product_review_reports WHERE review_id=?",
                Integer.class, reviewId));
        assertEquals("不实内容", jdbc.queryForObject("SELECT reason FROM product_review_reports WHERE id=?",
                String.class, reportId));
        mvc.perform(get("/admin/review-reports").session(session(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/review-reports").session(session(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(reportId))
                .andExpect(jsonPath("$.data.items[0].content").value("实际使用很好"))
                .andExpect(jsonPath("$.data.items[0].buyerUserId").doesNotExist());
        mvc.perform(post(resolvePath, reportId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"KEEP\",\"reason\":\"核实保留\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(resolvePath, reportId).with(csrf()).session(session(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"DELETE\",\"reason\":\"无效决定\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(resolvePath, reportId).with(csrf()).session(session(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"KEEP\",\"reason\":\"核实保留\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(resolvePath, reportId).with(csrf()).session(session(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"HIDE\",\"reason\":\"重复处理\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM product_review_moderation_audit WHERE report_id=?",
                Integer.class, reportId));
        mvc.perform(post(reportPath, reviewId).with(csrf()).session(session(secondReporter))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"违规图片\"}"))
                .andExpect(status().isOk());
        Long secondReportId = jdbc.queryForObject(
                "SELECT id FROM product_review_reports WHERE review_id=? AND reporter_user_id=?",
                Long.class, reviewId, secondReporter);
        mvc.perform(post(resolvePath, secondReportId).with(csrf()).session(session(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"HIDE\",\"reason\":\"核实隐藏\"}"))
                .andExpect(status().isOk());
        assertEquals(2, searchEvents(product.getId()));
        assertEquals(false, mediaAssets.isPubliclyReferenced(mediaId));
        mvc.perform(get("/media/{id}", mediaId).header("If-None-Match", mediaEtag))
                .andExpect(status().isNotFound());
        mvc.perform(get(listPath, product.getId())).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/products/{productId}/review-summary", product.getId()))
                .andExpect(jsonPath("$.data.reviewCount").value(0));
        mvc.perform(post(reportPath, reviewId).with(csrf()).session(session(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再次举报\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE product_reviews SET hidden_at=NULL, counted_in_summary=1 WHERE id=?", reviewId);
        reviews.addToSummary(product.getId(), 5);

        orders.insertCheckoutItem(order.getId(), sku.getId(), "第二件商品", "", 1, BigDecimal.TEN);
        Long secondItemId = jdbc.queryForObject("SELECT MAX(id) FROM order_items WHERE order_id=?", Long.class, order.getId());
        ProductReview second = new ProductReview();
        second.setOrderItemId(secondItemId);
        second.setProductId(product.getId());
        second.setBuyerUserId(buyer);
        second.setRating(4);
        second.setContent("第二条评价");
        // 该条由测试直接写库，补齐正常创建流程同时写入的汇总状态。
        second.setCountedInSummary(true);
        reviews.insertReview(second);
        reviews.addToSummary(product.getId(), second.getRating());
        mvc.perform(get("/products/{productId}/review-summary", product.getId()))
                .andExpect(jsonPath("$.data.reviewCount").value(2));
        assertEquals(0, reviews.selectSummary(product.getId()).averageRating().compareTo(new BigDecimal("4.50")));
        mvc.perform(get(listPath, product.getId()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(second.getId()))
                .andExpect(jsonPath("$.data.nextCursor").value(second.getId()))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get(listPath, product.getId()).param("size", "1").param("afterId", second.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(reviews.selectIdByOrderItemId(itemId)))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get(listPath, product.getId()).param("afterId", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(listPath, product.getId()).param("size", "101")).andExpect(status().isBadRequest());

        orders.insertCheckoutItem(order.getId(), sku.getId(), "第三件商品", "", 1, BigDecimal.TEN);
        Long refundedItem = jdbc.queryForObject("SELECT MAX(id) FROM order_items WHERE order_id=?", Long.class, order.getId());
        jdbc.update("INSERT INTO after_sale_cases (order_id, type, status, reason, refund_amount, requested_at) "
                + "VALUES (?, 'REFUND_ONLY', 'REFUNDED', '测试退款', 10, NOW())", order.getId());
        Long caseId = jdbc.queryForObject("SELECT MAX(id) FROM after_sale_cases WHERE order_id=?", Long.class, order.getId());
        jdbc.update("INSERT INTO after_sale_items (case_id, order_item_id, quantity, refund_amount) VALUES (?, ?, 1, 10)",
                caseId, refundedItem);
        mvc.perform(post(path, order.getId(), refundedItem).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        jdbc.update("INSERT INTO after_sale_cases (order_id, type, status, reason, refund_amount, requested_at) "
                + "VALUES (?, 'REFUND_ONLY', 'REFUNDED', '第二条评价对应退款', 10, NOW())", order.getId());
        Long secondCaseId = jdbc.queryForObject("SELECT MAX(id) FROM after_sale_cases WHERE order_id=?", Long.class, order.getId());
        jdbc.update("INSERT INTO after_sale_items (case_id, order_item_id, quantity, refund_amount) VALUES (?, ?, 1, 10)",
                secondCaseId, secondItemId);
        // 该退款由测试直接写库，模拟退款结算事务撤销评价计数。
        reviews.excludeFromSummary(second.getId());
        reviews.removeFromSummary(product.getId(), second.getRating());
        mvc.perform(get(listPath, product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/products/{productId}/review-summary", product.getId()))
                .andExpect(jsonPath("$.data.reviewCount").value(1));
        mvc.perform(post(followPath, second.getId()).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(followBody)).andExpect(status().isConflict());
        jdbc.update("UPDATE product_reviews SET hidden_at=NOW(), counted_in_summary=0 WHERE order_item_id=?", itemId);
        reviews.removeFromSummary(product.getId(), 5);
        assertEquals(false, mediaAssets.isPubliclyReferenced(mediaId));
        mvc.perform(get(listPath, product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        jdbc.update("UPDATE product_reviews SET hidden_at=NULL, counted_in_summary=1 WHERE id=?", reviewId);
        reviews.addToSummary(product.getId(), 5);
        mvc.perform(get(listPath, product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete("/reviews/{reviewId}", reviewId).with(csrf()).session(session(other)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/reviews/{reviewId}", reviewId).with(csrf()).session(session(buyer)))
                .andExpect(status().isOk());
        assertEquals(3, searchEvents(product.getId()));
        mvc.perform(delete("/reviews/{reviewId}", reviewId).with(csrf()).session(session(buyer)))
                .andExpect(status().isOk());
        assertEquals(false, mediaAssets.isPubliclyReferenced(mediaId));
        assertEquals(false, mediaAssets.isPubliclyReferenced(followMediaId));
        mvc.perform(get(listPath, product.getId())).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/products/{productId}/review-summary", product.getId()))
                .andExpect(jsonPath("$.data.reviewCount").value(0));
        mvc.perform(post(path, order.getId(), itemId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post(followPath, reviewId).with(csrf()).session(session(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(followBody)).andExpect(status().isConflict());
        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?", product.getId());
        mvc.perform(get(listPath, product.getId())).andExpect(status().isNotFound());
    }

    private Long user() {
        String username = "review-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
    }

    private int searchEvents(Long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='SEARCH_PRODUCT_REFRESH' "
                + "AND aggregate_id=?", Integer.class, productId);
    }

    private Long media(Long userId) throws Exception {
        String storageKey = UUID.randomUUID() + ".png";
        Path root = Path.of("target/review-media-tests");
        Files.createDirectories(root);
        Path file = root.resolve(storageKey);
        Files.write(file, new byte[]{1});
        mediaFiles.add(file);
        jdbc.update("INSERT INTO media_assets (uploaded_by_user_id, storage_key, mime_type, byte_size, width, height, sha256, status) "
                        + "VALUES (?, ?, 'image/png', 1, 1, 1, UNHEX(REPEAT('0', 64)), 'READY')",
                userId, storageKey);
        return jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_key=?", Long.class, storageKey);
    }

    @AfterEach
    void deleteMediaFiles() throws Exception {
        for (Path file : mediaFiles) Files.deleteIfExists(file);
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
