package com.shiwushop.shop;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShopProfileUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private ShopMapper shopMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void ownerEditsProfileWithoutChangingStatusOrOwnership() throws Exception {
        Long ownerId = insertUser("MERCHANT");
        Shop shop = insertShop(ownerId, "原店铺-" + UUID.randomUUID(), 0);
        Long oldMediaId = mediaId(ownerId, "READY");
        shop.setLogoMediaId(oldMediaId);
        shopMapper.updateById(shop);
        String newName = "新店铺-" + UUID.randomUUID();
        Long newMediaId = mediaId(ownerId, "READY");

        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", newName,
                                "logoMediaId", newMediaId, "description", "新的店铺简介"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(newName))
                .andExpect(jsonPath("$.data.logoMediaId").value(newMediaId))
                .andExpect(jsonPath("$.data.enabled").value(0));

        Shop updated = shopMapper.selectById(shop.getId());
        assertEquals(ownerId, updated.getOwnerUserId());
        assertEquals(0, updated.getEnabled());
        assertEquals("新的店铺简介", updated.getDescription());
        assertEquals(newMediaId, updated.getLogoMediaId());

        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", newName))))
                .andExpect(status().isOk());
        assertNull(shopMapper.selectById(shop.getId()).getLogoMediaId());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_SHOP_REFRESH' AND aggregate_id = ?",
                Integer.class, shop.getId()));
    }

    @Test
    void formerOwnerCannotEditAfterReassignment() throws Exception {
        Long oldOwnerId = insertUser("MERCHANT");
        Long newOwnerId = insertUser("MERCHANT");
        Shop shop = insertShop(oldOwnerId, "转让店铺-" + UUID.randomUUID(), 1);
        shop.setOwnerUserId(newOwnerId);
        shopMapper.updateById(shop);
        String body = om.writeValueAsString(Map.of("name", "新店名-" + UUID.randomUUID()));

        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(oldOwnerId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(newOwnerId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsDuplicateInvalidAndUnauthorizedUpdate() throws Exception {
        Long ownerId = insertUser("MERCHANT");
        Shop shop = insertShop(ownerId, "本人店铺-" + UUID.randomUUID(), 1);
        Shop other = insertShop(insertUser("MERCHANT"), "他人店铺-" + UUID.randomUUID(), 1);
        String duplicate = om.writeValueAsString(Map.of("name", other.getName()));

        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", " "))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/merchant/shops/{id}", shop.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/merchant/shops/{id}", other.getId()).session(sessionFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "旧地址", "logoUrl",
                                "https://example.com/logo.png"))))
                .andExpect(status().isBadRequest());
        for (Long mediaId : new Long[]{mediaId(ownerId, "STAGED"),
                mediaId(insertUser("MERCHANT"), "READY"), Long.MAX_VALUE}) {
            mockMvc.perform(put("/merchant/shops/{id}", shop.getId()).session(sessionFor(ownerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", shop.getName(),
                                    "logoMediaId", mediaId))))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(shop.getName(), shopMapper.selectById(shop.getId()).getName());
    }

    private Long insertUser(String role) {
        String username = "role_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private Long mediaId(Long userId, String status) {
        String key = "test/shop-profile-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), ?)
                """, userId, key, key, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }

    private Shop insertShop(Long ownerId, String name, int enabled) {
        Shop shop = new Shop();
        shop.setOwnerUserId(ownerId);
        shop.setName(name);
        shop.setEnabled(enabled);
        shopMapper.insert(shop);
        return shop;
    }

    private MockHttpSession sessionFor(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
