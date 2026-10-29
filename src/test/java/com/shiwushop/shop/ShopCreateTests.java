package com.shiwushop.shop;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
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
class ShopCreateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private ShopMapper shopMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void createsShopAndPromotesOwnerToMerchant() throws Exception {
        Long ownerId = insertUser("USER");
        Long adminId = insertUser("ADMIN");
        Long logoMediaId = mediaId(adminId, "READY");
        String name = "手测店铺-" + UUID.randomUUID();

        MvcResult response = mockMvc.perform(post("/admin/shops").session(sessionFor(adminId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "ownerUserId", ownerId, "name", name,
                                "logoMediaId", logoMediaId, "description", "数码店铺"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerUserId").value(ownerId))
                .andExpect(jsonPath("$.data.logoMediaId").value(logoMediaId))
                .andExpect(jsonPath("$.data.logoUrl").doesNotExist())
                .andExpect(jsonPath("$.data.enabled").value(1))
                .andReturn();

        long shopId = om.readTree(response.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        Shop shop = shopMapper.selectById(shopId);
        assertNotNull(shop);
        assertEquals(name, shop.getName());
        assertEquals(logoMediaId, shop.getLogoMediaId());
        assertEquals("MERCHANT", role(ownerId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_SHOP_REFRESH' AND aggregate_id = ?",
                Integer.class, shopId));
    }

    @Test
    void rejectsDuplicateMissingOwnerAndInvalidInput() throws Exception {
        Long adminId = insertUser("ADMIN");
        MockHttpSession admin = sessionFor(adminId);
        Long ownerId = insertUser("USER");
        Long secondOwnerId = insertUser("USER");
        String name = "店铺-" + UUID.randomUUID();
        String first = om.writeValueAsString(Map.of("ownerUserId", ownerId, "name", name));

        mockMvc.perform(post("/admin/shops").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/shops").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", secondOwnerId, "name", name))))
                .andExpect(status().isConflict());
        assertEquals("USER", role(secondOwnerId));

        mockMvc.perform(post("/admin/shops").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", Long.MAX_VALUE, "name", "其他店"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/shops").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "ownerUserId", insertUser("ADMIN"), "name", "管理账号店-" + UUID.randomUUID()))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/admin/shops").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", secondOwnerId, "name", " "))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/shops").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", secondOwnerId, "name", "旧标识",
                                "logoUrl", "https://example.com/logo.png"))))
                .andExpect(status().isBadRequest());
        for (Long mediaId : new Long[]{mediaId(adminId, "STAGED"),
                mediaId(secondOwnerId, "READY"), Long.MAX_VALUE}) {
            mockMvc.perform(post("/admin/shops").session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("ownerUserId", secondOwnerId,
                                    "name", "非法标识-" + UUID.randomUUID(), "logoMediaId", mediaId))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void creationRequiresAdmin() throws Exception {
        Long ownerId = insertUser("USER");
        String body = om.writeValueAsString(Map.of(
                "ownerUserId", ownerId, "name", "店铺-" + UUID.randomUUID()));

        mockMvc.perform(post("/admin/shops")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/shops").session(sessionFor(insertUser("USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals("USER", role(ownerId));
    }

    private Long insertUser(String role) {
        String username = "role_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private String role(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT role FROM user_accounts WHERE user_id = ?", String.class, userId);
    }

    private Long mediaId(Long userId, String status) {
        String key = "test/shop-create-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), ?)
                """, userId, key, key, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }

    private MockHttpSession sessionFor(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
