package com.shiwushop.product;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.catalog.entity.status.ProductStatus;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.account.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantProductListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ProductMapper productMapper;

    @Test
    void listsAllStatusesOfOwnShopWithStablePagination() throws Exception {
        Long owner = user("MERCHANT");
        Shop shop = shop(owner);
        Category category = category();
        Product draft = product(shop, category, "DRAFT");
        Product onSale = product(shop, category, "ON_SALE");
        Product offSale = product(shop, category, "OFF_SALE");
        Product foreign = product(shop(user("MERCHANT")), category, "ON_SALE");
        MockHttpSession session = session(owner);

        mockMvc.perform(get("/merchant/products")
                        .param("shopId", shop.getId().toString())
                        .param("page", "1").param("size", "2").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(offSale.getId()))
                .andExpect(jsonPath("$.data.items[1].id").value(onSale.getId()))
                .andExpect(jsonPath("$.data.items[*].id").value(not(hasItem(foreign.getId().intValue()))));

        mockMvc.perform(get("/merchant/products")
                        .param("shopId", shop.getId().toString())
                        .param("page", "2").param("size", "2").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(draft.getId()));
    }

    @Test
    void rejectsAnonymousForeignShopAndInvalidPage() throws Exception {
        Long owner = user("MERCHANT");
        Shop shop = shop(owner);
        String shopId = shop.getId().toString();

        mockMvc.perform(get("/merchant/products").param("shopId", shopId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/merchant/products").param("shopId", shopId)
                        .session(session(user("MERCHANT"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/merchant/products").param("shopId", shopId)
                        .session(session(user("USER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/merchant/products").param("shopId", shopId)
                        .param("page", "0").session(session(owner)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/merchant/products").param("shopId", shopId)
                        .param("size", "101").session(session(owner)))
                .andExpect(status().isBadRequest());
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

    private Category category() {
        Category category = new Category();
        category.setName("分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    private Product product(Shop shop, Category category, String status) {
        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(category.getId());
        product.setName("商品-" + UUID.randomUUID());
        product.setStatus(ProductStatus.valueOf(status));
        productMapper.insert(product);
        return product;
    }

    private MockHttpSession session(Long user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }
}
