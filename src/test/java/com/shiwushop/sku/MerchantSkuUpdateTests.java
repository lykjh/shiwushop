package com.shiwushop.sku;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.service.RegisterService;
import org.junit.jupiter.api.Test;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantSkuUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private RegisterService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private ProductOptionMapper optionMapper;
    @Autowired private ProductOptionValueMapper valueMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private SkuOptionValueMapper linkMapper;

    @Test
    void changesOnlyPriceAndStockAndKeepsOptionOrder() throws Exception {
        Fixture f = fixture();
        String body = "{\"price\":3899.00,\"stock\":8}";

        mockMvc.perform(put("/merchant/products/{productId}/skus/{skuId}", f.productId, f.skuId)
                        .session(f.session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(f.skuId))
                .andExpect(jsonPath("$.data.productId").value(f.productId))
                .andExpect(jsonPath("$.data.optionValueIds[0]").value(f.colorId))
                .andExpect(jsonPath("$.data.optionValueIds[1]").value(f.capacityId))
                .andExpect(jsonPath("$.data.price").value(3899.00))
                .andExpect(jsonPath("$.data.stock").value(8));

        Sku saved = skuMapper.selectById(f.skuId);
        assertEquals(new BigDecimal("3899.00"), saved.getPrice());
        assertEquals(8, saved.getStock());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((f.colorId + "," + f.capacityId).getBytes(StandardCharsets.UTF_8))),
                saved.getOptionSignature());
        assertEquals(List.of(f.colorId, f.capacityId), linkMapper.selectValueIds(f.skuId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_movements WHERE sku_id = ? AND reason = 'MERCHANT_ADJUST' " +
                        "AND delta = 5 AND balance_after = 8", Integer.class, f.skuId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, f.productId));
    }

    @Test
    void recordsOnlyActualStockChanges() throws Exception {
        Fixture f = fixture();
        String path = "/merchant/products/{productId}/skus/{skuId}";
        mockMvc.perform(put(path, f.productId, f.skuId).session(f.session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":20.00,\"stock\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(path, f.productId, f.skuId).session(f.session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":21.00,\"stock\":1}"))
                .andExpect(status().isOk());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_movements WHERE sku_id=? AND reason='MERCHANT_ADJUST' "
                        + "AND delta=-2 AND balance_after=1", Integer.class, f.skuId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_movements WHERE sku_id=? AND reason='MERCHANT_ADJUST'",
                Integer.class, f.skuId));
    }

    @Test
    void rejectsAnonymousForeignProductOrSkuAndInvalidValues() throws Exception {
        Fixture f = fixture();
        String path = "/merchant/products/{productId}/skus/{skuId}";
        String valid = "{\"price\":19.90,\"stock\":0}";

        mockMvc.perform(put(path, f.productId, f.skuId)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(path, f.productId, f.skuId)
                        .session(session(user())).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(path, f.productId, f.skuId + 999999)
                        .session(f.session).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(path, f.productId + 999999, f.skuId)
                        .session(f.session).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isNotFound());

        for (String bad : List.of(
                "{\"price\":0,\"stock\":8}",
                "{\"price\":19.90,\"stock\":-1}",
                "{\"price\":19.999,\"stock\":8}",
                "{\"stock\":8}")) {
            mockMvc.perform(put(path, f.productId, f.skuId)
                            .session(f.session).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(new BigDecimal("3999.00"), skuMapper.selectById(f.skuId).getPrice());
    }

    private Fixture fixture() throws Exception {
        Long owner = user();
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);

        Category category = new Category();
        category.setName("分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);

        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);

        ProductOption color = option(product, "颜色", 0);
        ProductOption capacity = option(product, "容量", 1);
        ProductOptionValue white = value(color, "白色");
        ProductOptionValue small = value(capacity, "128GB");

        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setOptionSignature(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((white.getId() + "," + small.getId()).getBytes(StandardCharsets.UTF_8))));
        sku.setPrice(new BigDecimal("3999.00"));
        sku.setStock(3);
        skuMapper.insert(sku);
        linkMapper.insertLink(sku.getId(), white.getId());
        linkMapper.insertLink(sku.getId(), small.getId());
        return new Fixture(session(owner), product.getId(), sku.getId(), white.getId(), small.getId());
    }

    private Long user() {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = 'MERCHANT' WHERE username = ?", username);
        return jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }

    private ProductOption option(Product product, String name, int order) {
        ProductOption option = new ProductOption();
        option.setProductId(product.getId());
        option.setName(name);
        option.setSortOrder(order);
        optionMapper.insert(option);
        return option;
    }

    private ProductOptionValue value(ProductOption option, String name) {
        ProductOptionValue value = new ProductOptionValue();
        value.setOptionId(option.getId());
        value.setValueName(name);
        value.setSortOrder(0);
        valueMapper.insert(value);
        return value;
    }

    private record Fixture(MockHttpSession session, Long productId, Long skuId,
                           Long colorId, Long capacityId) {}
}
