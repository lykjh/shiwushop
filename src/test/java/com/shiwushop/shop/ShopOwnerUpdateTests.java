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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShopOwnerUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private ShopMapper shopMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void reassignsShopAndPromotesNewOwner() throws Exception {
        Long oldOwnerId = insertUser("MERCHANT");
        Long newOwnerId = insertUser("USER");
        Shop shop = insertShop(oldOwnerId);

        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId())
                        .session(sessionFor(insertUser("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", newOwnerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerUserId").value(newOwnerId));

        assertEquals(newOwnerId, shopMapper.selectById(shop.getId()).getOwnerUserId());
        assertEquals("MERCHANT", role(newOwnerId));
        assertEquals("MERCHANT", role(oldOwnerId));
    }

    @Test
    void rejectsMissingShopMissingOwnerAdminOwnerAndInvalidId() throws Exception {
        MockHttpSession admin = sessionFor(insertUser("ADMIN"));
        Long oldOwnerId = insertUser("MERCHANT");
        Shop shop = insertShop(oldOwnerId);
        Long newOwnerId = insertUser("USER");
        String valid = om.writeValueAsString(Map.of("ownerUserId", newOwnerId));

        mockMvc.perform(put("/admin/shops/{id}/owner", Long.MAX_VALUE).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", Long.MAX_VALUE))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", insertUser("ADMIN")))))
                .andExpect(status().isConflict());
        Long disabledId = insertUser("USER");
        jdbcTemplate.update("UPDATE user_accounts SET status = 'DISABLED' WHERE user_id = ?", disabledId);
        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", disabledId))))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ownerUserId", -1))))
                .andExpect(status().isBadRequest());

        assertEquals(oldOwnerId, shopMapper.selectById(shop.getId()).getOwnerUserId());
        assertEquals("USER", role(newOwnerId));
    }

    @Test
    void reassignmentRequiresAdmin() throws Exception {
        Long oldOwnerId = insertUser("MERCHANT");
        Long newOwnerId = insertUser("USER");
        Shop shop = insertShop(oldOwnerId);
        String body = om.writeValueAsString(Map.of("ownerUserId", newOwnerId));

        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/shops/{id}/owner", shop.getId())
                        .session(sessionFor(insertUser("MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(oldOwnerId, shopMapper.selectById(shop.getId()).getOwnerUserId());
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

    private Shop insertShop(Long ownerId) {
        Shop shop = new Shop();
        shop.setOwnerUserId(ownerId);
        shop.setName("归属测试-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        return shop;
    }

    private MockHttpSession sessionFor(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
