package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.entity.category.Category;
import com.shiwushop.entity.product.Product;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.product.ProductMapper;
import com.shiwushop.mapper.shop.ShopMapper;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminProductStatusTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;

    @Test
    void adminTakesDownProductRepeatedly() throws Exception {
        Product product = product("ON_SALE");
        MockHttpSession admin = session(user("ADMIN"));

        mockMvc.perform(request(product.getId(), "OFF_SALE").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"));
        assertEquals(ProductStatus.OFF_SALE, productMapper.selectById(product.getId()).getStatus());

        mockMvc.perform(request(product.getId(), "OFF_SALE").session(admin))
                .andExpect(status().isOk());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, product.getId()));
    }

    @Test
    void rejectsOtherStatusesAndMissingProductWithoutChangingData() throws Exception {
        Product product = product("ON_SALE");
        MockHttpSession admin = session(user("ADMIN"));

        mockMvc.perform(request(product.getId(), "ON_SALE").session(admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(request(product.getId(), "DRAFT").session(admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(request(Long.MAX_VALUE, "OFF_SALE").session(admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(request(0L, "OFF_SALE").session(admin))
                .andExpect(status().isBadRequest());
        assertEquals(ProductStatus.ON_SALE, productMapper.selectById(product.getId()).getStatus());
    }

    @Test
    void requiresAdmin() throws Exception {
        Product product = product("ON_SALE");
        mockMvc.perform(request(product.getId(), "OFF_SALE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request(product.getId(), "OFF_SALE")
                        .session(session(user("MERCHANT"))))
                .andExpect(status().isForbidden());
        assertEquals(ProductStatus.ON_SALE, productMapper.selectById(product.getId()).getStatus());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            Long id, String nextStatus) {
        return put("/admin/products/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + nextStatus + "\"}");
    }

    private Product product(String status) {
        Category category = new Category();
        category.setName("分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Product product = new Product();
        Shop shop = new Shop();
        shop.setOwnerUserId(user("MERCHANT"));
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.valueOf(status));
        productMapper.insert(product);
        return product;
    }

    private Long user(String role) {
        String username = "merchant_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }
}
