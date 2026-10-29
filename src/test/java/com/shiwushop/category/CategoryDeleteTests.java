package com.shiwushop.category;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryDeleteTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopMapper shopMapper;

    @Test
    void deletesEmptyCategoryAndReturns404ForMissingCategory() throws Exception {
        Category category = insertCategory(null, 1);

        mockMvc.perform(delete("/admin/categories/{id}", category.getId()).session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertNull(categoryMapper.selectById(category.getId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='SEARCH_CATEGORY_REFRESH' AND aggregate_id=?", Long.class, category.getId()));

        mockMvc.perform(delete("/admin/categories/{id}", category.getId()).session(adminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsCategoryWithChildrenOrProducts() throws Exception {
        MockHttpSession admin = adminSession();
        Category parent = insertCategory(null, 1);
        Category child = insertCategory(parent.getId(), 2);
        String username = "shop-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long ownerId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        Shop shop = new Shop();
        shop.setName("删除测试店铺-" + UUID.randomUUID());
        shop.setOwnerUserId(ownerId);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(child.getId());
        product.setName("测试商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);

        mockMvc.perform(delete("/admin/categories/{id}", parent.getId()).session(admin))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/admin/categories/{id}", child.getId()).session(admin))
                .andExpect(status().isConflict());

        assertNotNull(categoryMapper.selectById(parent.getId()));
        assertNotNull(categoryMapper.selectById(child.getId()));
    }

    @Test
    void deletionRequiresAdmin() throws Exception {
        Category category = insertCategory(null, 1);

        mockMvc.perform(delete("/admin/categories/{id}", category.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/categories/{id}", category.getId()).session(sessionForRole("USER")))
                .andExpect(status().isForbidden());
        assertNotNull(categoryMapper.selectById(category.getId()));
    }

    private Category insertCategory(Long parentId, int level) {
        Category category = new Category();
        category.setName("删除测试-" + UUID.randomUUID());
        category.setParentId(parentId);
        category.setLevel(level);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    private MockHttpSession adminSession() {
        return sessionForRole("ADMIN");
    }

    private MockHttpSession sessionForRole(String role) {
        String username = "role-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        jdbc.update("UPDATE user_accounts SET role=? WHERE user_id=?", role, userId);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
