package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.status.ProductStatus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantProductOptionsTests {
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

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void ownerReplacesOptionsAndReceivesValueIdsInInputOrder() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        ProductOption old = option(product, "旧规格", 0);
        value(old, "旧值", 0);

        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("options", List.of(
                                Map.of("name", "颜色", "values", List.of("白色", "黑色")),
                                Map.of("name", "容量", "values", List.of("128GB", "256GB")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isNumber())
                .andExpect(jsonPath("$.data[0].name").value("颜色"))
                .andExpect(jsonPath("$.data[0].values[0].id").isNumber())
                .andExpect(jsonPath("$.data[0].values[0].value").value("白色"))
                .andExpect(jsonPath("$.data[0].values[1].value").value("黑色"))
                .andExpect(jsonPath("$.data[1].name").value("容量"))
                .andExpect(jsonPath("$.data[1].values[0].value").value("128GB"));

        List<ProductOption> options = optionMapper.selectList(
                new LambdaQueryWrapper<ProductOption>()
                        .eq(ProductOption::getProductId, product.getId())
                        .orderByAsc(ProductOption::getSortOrder));
        assertEquals(2, options.size());
        assertEquals("颜色", options.get(0).getName());
        assertEquals(0, options.get(0).getSortOrder());
        assertEquals("容量", options.get(1).getName());
        assertEquals(1, options.get(1).getSortOrder());
        assertEquals(0, valueMapper.selectCount(new LambdaQueryWrapper<ProductOptionValue>()
                .eq(ProductOptionValue::getOptionId, old.getId())));
    }

    @Test
    void requiresOwnerAndRejectsReplacementAfterSkuExists() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        ProductOption existing = option(product, "颜色", 0);
        value(existing, "白色", 0);
        String body = oneOption("容量", List.of("128GB"));

        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session(user("MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setOptionSignature("0000000000000000000000000000000000000000000000000000000000000000");
        sku.setPrice(new BigDecimal("19.90"));
        sku.setStock(1);
        skuMapper.insert(sku);
        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertEquals(1, optionMapper.selectCount(new LambdaQueryWrapper<ProductOption>()
                .eq(ProductOption::getProductId, product.getId())));
    }

    @Test
    void rejectsDuplicateAndEmptyOptionsWithoutChangingSavedOptions() throws Exception {
        Long owner = user("MERCHANT");
        Product product = product(shop(owner));
        ProductOption existing = option(product, "旧规格", 0);
        MockHttpSession session = session(owner);

        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("options", List.of(
                                Map.of("name", "颜色", "values", List.of("白色")),
                                Map.of("name", " 颜色 ", "values", List.of("黑色")))))))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(oneOption("颜色", List.of("白色", " 白色 "))))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(oneOption("颜色", List.of())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/merchant/products/{id}/options", product.getId())
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("options", List.of()))))
                .andExpect(status().isBadRequest());
        assertEquals("旧规格", optionMapper.selectById(existing.getId()).getName());
    }

    private String oneOption(String name, List<String> values) {
        return om.writeValueAsString(Map.of("options", List.of(
                Map.of("name", name, "values", values))));
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

    private ProductOption option(Product product, String name, int sortOrder) {
        ProductOption option = new ProductOption();
        option.setProductId(product.getId());
        option.setName(name);
        option.setSortOrder(sortOrder);
        optionMapper.insert(option);
        return option;
    }

    private void value(ProductOption option, String name, int sortOrder) {
        ProductOptionValue value = new ProductOptionValue();
        value.setOptionId(option.getId());
        value.setValueName(name);
        value.setSortOrder(sortOrder);
        valueMapper.insert(value);
    }
}
