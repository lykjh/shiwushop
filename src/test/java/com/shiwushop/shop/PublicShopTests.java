package com.shiwushop.shop;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.account.service.RegisterService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicShopTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private RegisterService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void listPaginatesOpenShopsWithVisibleOnSaleProducts() throws Exception {
        Category root = category(null, 1, 1);
        Category child = category(root.getId(), 2, 1);
        Category hidden = category(root.getId(), 2, 0);
        Shop first = shop(1);
        Shop second = shop(1);
        Shop paused = shop(0);
        Shop draftOnly = shop(1);
        Shop hiddenOnly = shop(1);

        product(child, first, "ON_SALE");
        product(child, first, "ON_SALE");
        product(child, second, "ON_SALE");
        product(child, paused, "ON_SALE");
        product(child, draftOnly, "DRAFT");
        product(hidden, hiddenOnly, "ON_SALE");

        mockMvc.perform(get("/shops")
                        .param("categoryId", root.getId().toString())
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.getId()))
                .andExpect(jsonPath("$.data.items[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1]").doesNotExist());

        mockMvc.perform(get("/shops")
                        .param("categoryId", root.getId().toString())
                        .param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(second.getId()));

        mockMvc.perform(get("/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").isNumber())
                .andExpect(jsonPath("$.data.items[0].ownerUserId").doesNotExist());
    }

    @Test
    void detailShowsPausedShopAndOnlyItsVisibleOnSaleCategories() throws Exception {
        Category root = category(null, 1, 1);
        Category visible = category(root.getId(), 2, 1);
        Category hidden = category(root.getId(), 2, 0);
        Category draft = category(root.getId(), 2, 1);
        Shop shop = shop(0);
        Long mediaId = mediaId(shop.getOwnerUserId());
        shop.setLogoMediaId(mediaId);
        shopMapper.updateById(shop);
        product(visible, shop, "ON_SALE");
        product(hidden, shop, "ON_SALE");
        product(draft, shop, "DRAFT");

        mockMvc.perform(get("/shops/{id}", shop.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(shop.getId()))
                .andExpect(jsonPath("$.data.enabled").value(0))
                .andExpect(jsonPath("$.data.logoMediaId").value(mediaId))
                .andExpect(jsonPath("$.data.logoUrl").doesNotExist())
                .andExpect(jsonPath("$.data.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.data.categories[?(@.id == " + visible.getId() + ")].name")
                        .value(hasItem(visible.getName())))
                .andExpect(jsonPath("$.data.categories[?(@.id == " + hidden.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.data.categories[?(@.id == " + draft.getId() + ")]").isEmpty());
    }

    @Test
    void rejectsInvalidPaginationHiddenCategoryAndMissingShop() throws Exception {
        Category hidden = category(null, 1, 0);
        Category child = category(hidden.getId(), 2, 1);

        mockMvc.perform(get("/shops").param("page", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/shops").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/shops").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/shops").param("categoryId", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/shops").param("categoryId", child.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/shops").param("categoryId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/shops/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/shops/{id}", 0))
                .andExpect(status().isBadRequest());
    }

    private Category category(Long parentId, int level, int visible) {
        Category category = new Category();
        category.setName("分类-" + UUID.randomUUID());
        category.setParentId(parentId);
        category.setLevel(level);
        category.setVisible(visible);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    private Shop shop(int enabled) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        Shop shop = new Shop();
        shop.setOwnerUserId(ownerId);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(enabled);
        shopMapper.insert(shop);
        return shop;
    }

    private void product(Category category, Shop shop, String status) {
        Product product = new Product();
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.valueOf(status));
        productMapper.insert(product);
    }

    private Long mediaId(Long userId) {
        String key = "test/public-shop-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), 'READY')
                """, userId, key, key);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }
}
