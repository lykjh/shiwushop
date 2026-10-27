package com.shiwushop.brand;

import com.shiwushop.entity.brand.Brand;
import com.shiwushop.entity.category.Category;
import com.shiwushop.mapper.brand.BrandMapper;
import com.shiwushop.mapper.category.CategoryMapper;
import org.junit.jupiter.api.Test;
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
class PublicBrandListTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private BrandMapper brandMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void listsOnlyEnabledBrandsWithoutCategoryOrLogin() throws Exception {
        Brand enabled = brand(1);
        Brand disabled = brand(0);

        mockMvc.perform(get("/brands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + enabled.getId() + ")].name")
                        .value(hasItem(enabled.getName())))
                .andExpect(jsonPath("$.data[?(@.id == " + enabled.getId() + ")].enabled").isEmpty())
                .andExpect(jsonPath("$.data[?(@.id == " + enabled.getId() + ")].logoUrl").isEmpty())
                .andExpect(jsonPath("$.data[?(@.id == " + disabled.getId() + ")]").isEmpty());
    }

    @Test
    void categoryIncludesVisibleDescendantsAndOnlyLiveProductsFromOpenShops() throws Exception {
        Category root = category(null, 1, 1);
        Category child = category(root.getId(), 2, 1);
        Category grandchild = category(child.getId(), 3, 1);
        Category hidden = category(root.getId(), 2, 0);
        Long open = shop(1);
        Long paused = shop(0);
        Brand live = brand(1);
        Brand disabledButUsed = brand(0);
        Brand pausedShop = brand(1);
        Brand draft = brand(1);
        Brand hiddenCategory = brand(1);

        product(child, live, open, "ON_SALE");
        product(grandchild, disabledButUsed, open, "ON_SALE");
        product(child, pausedShop, paused, "ON_SALE");
        product(child, draft, open, "DRAFT");
        product(hidden, hiddenCategory, open, "ON_SALE");

        mockMvc.perform(get("/brands").param("categoryId", root.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + live.getId() + ")].name")
                        .value(hasItem(live.getName())))
                .andExpect(jsonPath("$.data[?(@.id == " + disabledButUsed.getId() + ")].name")
                        .value(hasItem(disabledButUsed.getName())))
                .andExpect(jsonPath("$.data[?(@.id == " + pausedShop.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.id == " + draft.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.id == " + hiddenCategory.getId() + ")]").isEmpty());

        mockMvc.perform(get("/brands").param("categoryId", child.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + disabledButUsed.getId() + ")].name")
                        .value(hasItem(disabledButUsed.getName())));
    }

    @Test
    void rejectsInvalidMissingAndHiddenCategory() throws Exception {
        Category hidden = category(null, 1, 0);
        Category child = category(hidden.getId(), 2, 1);

        mockMvc.perform(get("/brands").param("categoryId", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/brands").param("categoryId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/brands").param("categoryId", child.getId().toString()))
                .andExpect(status().isNotFound());
    }

    private Brand brand(int enabled) {
        Brand brand = new Brand();
        brand.setName("品牌-" + UUID.randomUUID());
        brand.setEnabled(enabled);
        brandMapper.insert(brand);
        return brand;
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

    private Long shop(int enabled) {
        String nickname = "merchant-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users(nickname) VALUES (?)", nickname);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
        String name = "店铺-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shops(owner_user_id, name, enabled) VALUES (?, ?, ?)",
                ownerId, name, enabled);
        return jdbcTemplate.queryForObject("SELECT id FROM shops WHERE name = ?", Long.class, name);
    }

    private void product(Category category, Brand brand, Long shopId, String status) {
        String name = "商品-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products(shop_id, category_id, name, status) VALUES (?, ?, ?, ?)",
                shopId, category.getId(), name, status);
        Long productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO product_brands(product_id, brand_id) VALUES (?, ?)",
                productId, brand.getId());
    }
}
