package com.shiwushop.sku;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.account.service.UserService;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantSkuCreateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private ProductOptionMapper optionMapper;
    @Autowired private ProductOptionValueMapper valueMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private SkuOptionValueMapper linkMapper;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void createsSkuInProductOptionOrderAndRejectsDuplicateCombination() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        ProductOption color = option(product, "颜色", 0);
        ProductOption capacity = option(product, "容量", 1);
        ProductOptionValue white = value(color, "白色", 0);
        value(color, "黑色", 1);
        ProductOptionValue small = value(capacity, "128GB", 0);
        String body = body(List.of(small.getId(), white.getId()), "3999.00", 0);

        mockMvc.perform(post("/merchant/products/{id}/skus", product.getId())
                        .session(session(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.productId").value(product.getId()))
                .andExpect(jsonPath("$.data.optionValueIds[0]").value(white.getId()))
                .andExpect(jsonPath("$.data.optionValueIds[1]").value(small.getId()))
                .andExpect(jsonPath("$.data.price").value(3999.00))
                .andExpect(jsonPath("$.data.stock").value(0));

        Sku saved = skuMapper.selectOne(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getProductId, product.getId()));
        assertNotNull(saved);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((white.getId() + "," + small.getId()).getBytes(StandardCharsets.UTF_8))),
                saved.getOptionSignature());
        assertEquals(List.of(white.getId(), small.getId()),
                linkMapper.selectValueIds(saved.getId()));

        mockMvc.perform(post("/merchant/products/{id}/skus", product.getId())
                        .session(session(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(white.getId(), small.getId()), "3999.00", 1)))
                .andExpect(status().isConflict());
        assertEquals(1, skuMapper.selectCount(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getProductId, product.getId())));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, product.getId()));
    }

    @Test
    void rejectsMissingOrForeignValuesAndTwoValuesFromSameOption() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        ProductOption color = option(product, "颜色", 0);
        ProductOption capacity = option(product, "容量", 1);
        ProductOptionValue white = value(color, "白色", 0);
        ProductOptionValue black = value(color, "黑色", 1);
        ProductOptionValue small = value(capacity, "128GB", 0);
        Product otherProduct = product(shop(user("MERCHANT")));
        ProductOption otherOption = option(otherProduct, "颜色", 0);
        ProductOptionValue foreign = value(otherOption, "红色", 0);
        MockHttpSession session = session(owner);

        for (List<Long> ids : List.of(
                List.of(white.getId()),
                List.of(white.getId(), black.getId()),
                List.of(white.getId(), foreign.getId()),
                List.of(white.getId(), white.getId()))) {
            mockMvc.perform(post("/merchant/products/{id}/skus", product.getId())
                            .session(session).contentType(MediaType.APPLICATION_JSON)
                            .content(body(ids, "19.90", 1)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/merchant/products/{id}/skus", product.getId())
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(white.getId(), small.getId()), "0", 1)))
                .andExpect(status().isBadRequest());
        assertEquals(0, skuMapper.selectCount(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getProductId, product.getId())));
    }

    @Test
    void requiresLoginAndShopOwnership() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        ProductOption color = option(product, "颜色", 0);
        ProductOptionValue white = value(color, "白色", 0);
        String body = body(List.of(white.getId()), "19.90", 1);

        mockMvc.perform(post("/merchant/products/{id}/skus", product.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/merchant/products/{id}/skus", product.getId())
                        .session(session(user("MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    private String body(List<Long> ids, String price, int stock) {
        return om.writeValueAsString(Map.of(
                "optionValueIds", ids,
                "price", new BigDecimal(price),
                "stock", stock));
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }

    private Long user(String role) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private Shop shop(Long owner) {
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        return shop;
    }

    private Product product(Shop shop) {
        Category root = new Category();
        root.setName("一级-" + UUID.randomUUID());
        root.setLevel(1);
        root.setVisible(1);
        root.setSortOrder(0);
        categoryMapper.insert(root);
        Category child = new Category();
        child.setName("二级-" + UUID.randomUUID());
        child.setParentId(root.getId());
        child.setLevel(2);
        child.setVisible(1);
        child.setSortOrder(0);
        categoryMapper.insert(child);

        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(child.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);
        return product;
    }

    private ProductOption option(Product product, String name, int order) {
        ProductOption option = new ProductOption();
        option.setProductId(product.getId());
        option.setName(name);
        option.setSortOrder(order);
        optionMapper.insert(option);
        return option;
    }

    private ProductOptionValue value(ProductOption option, String name, int order) {
        ProductOptionValue value = new ProductOptionValue();
        value.setOptionId(option.getId());
        value.setValueName(name);
        value.setSortOrder(order);
        valueMapper.insert(value);
        return value;
    }
}
