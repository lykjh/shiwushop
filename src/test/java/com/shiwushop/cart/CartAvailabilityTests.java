package com.shiwushop.cart;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CartAvailabilityTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private ProductOptionMapper optionMapper;
    @Autowired private ProductOptionValueMapper valueMapper;
    @Autowired private SkuOptionValueMapper linkMapper;
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void showsOptionsAndAvailabilityWhenStockOrShopChanges() throws Exception {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long buyerId = jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", buyerId);

        Category category = new Category();
        category.setName("购物车分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Shop shop = new Shop();
        shop.setName("购物车店铺-" + UUID.randomUUID());
        shop.setOwnerUserId(buyerId);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Product product = new Product();
        product.setName("测试手机");
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setStatus(ProductStatus.ON_SALE);
        productMapper.insert(product);
        ProductOption option = new ProductOption();
        option.setProductId(product.getId());
        option.setName("颜色");
        option.setSortOrder(0);
        optionMapper.insert(option);
        ProductOptionValue value = new ProductOptionValue();
        value.setOptionId(option.getId());
        value.setValueName("白色");
        value.setSortOrder(0);
        valueMapper.insert(value);
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        String hex = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(hex + hex);
        sku.setPrice(new BigDecimal("3999.00"));
        sku.setStock(2);
        skuMapper.insert(sku);
        linkMapper.insertLink(sku.getId(), value.getId());

        String body = om.writeValueAsString(Map.of("skuId", sku.getId(), "quantity", 2));
        MvcResult added = mockMvc.perform(post("/cart/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.getId()))
                .andExpect(jsonPath("$.data.optionValues[0]").value("白色"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.stock").doesNotExist())
                .andReturn();
        long itemId = om.readTree(added.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(put("/cart/items/{id}", itemId).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(1));
        mockMvc.perform(put("/cart/items/{id}", itemId).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk());

        sku.setStock(1);
        skuMapper.updateById(sku);
        mockMvc.perform(get("/cart/items").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].available").value(false))
                .andExpect(jsonPath("$.data[0].unavailableReason").value("库存不足"));
        shop.setEnabled(0);
        shopMapper.updateById(shop);
        mockMvc.perform(get("/cart/items").session(session))
                .andExpect(jsonPath("$.data[0].unavailableReason").value("店铺已暂停营业"));
        shop.setEnabled(1);
        shopMapper.updateById(shop);
        category.setVisible(0);
        categoryMapper.updateById(category);
        mockMvc.perform(get("/cart/items").session(session))
                .andExpect(jsonPath("$.data[0].unavailableReason").value("分类已隐藏"));
        mockMvc.perform(post("/cart/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(
                                om.writeValueAsString(Map.of("skuId", sku.getId(), "quantity", 1))))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/cart/items/{id}", itemId).session(session))
                .andExpect(status().isOk());
    }
}
