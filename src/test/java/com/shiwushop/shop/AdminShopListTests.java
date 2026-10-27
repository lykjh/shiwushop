package com.shiwushop.shop;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
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
class AdminShopListTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private ShopMapper shopMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void listsPausedShopsForAdmin() throws Exception {
        Long ownerId = insertUser("MERCHANT");
        Long adminId = insertUser("ADMIN");
        Long mediaId = mediaId(adminId);
        Shop shop = new Shop();
        shop.setOwnerUserId(ownerId);
        shop.setName("暂停店铺-" + UUID.randomUUID());
        shop.setLogoMediaId(mediaId);
        shop.setEnabled(0);
        shopMapper.insert(shop);

        mockMvc.perform(get("/admin/shops").session(sessionFor(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + shop.getId() + ")].enabled").value(hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.id == " + shop.getId() + ")].ownerUserId")
                        .value(hasItem(ownerId.intValue())))
                .andExpect(jsonPath("$.data[?(@.id == " + shop.getId() + ")].logoMediaId")
                        .value(hasItem(mediaId.intValue())));
    }

    @Test
    void listRequiresAdmin() throws Exception {
        mockMvc.perform(get("/admin/shops"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/shops").session(sessionFor(insertUser("MERCHANT"))))
                .andExpect(status().isForbidden());
    }

    private Long insertUser(String role) {
        String username = "role_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private Long mediaId(Long userId) {
        String key = "test/shop-list-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), 'READY')
                """, userId, key, key);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }

    private MockHttpSession sessionFor(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
