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
class ShopStatusUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private ShopMapper shopMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void pausesAndResumesShop() throws Exception {
        Shop shop = insertShop();
        MockHttpSession admin = sessionFor(insertUser("ADMIN"));

        mockMvc.perform(put("/admin/shops/{id}/status", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("enabled", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(0));
        assertEquals(0, shopMapper.selectById(shop.getId()).getEnabled());

        mockMvc.perform(put("/admin/shops/{id}/status", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("enabled", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(1));
        assertEquals(1, shopMapper.selectById(shop.getId()).getEnabled());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_SHOP_REFRESH' AND aggregate_id = ?",
                Integer.class, shop.getId()));
    }

    @Test
    void rejectsMissingShopAndInvalidStatus() throws Exception {
        Shop shop = insertShop();
        MockHttpSession admin = sessionFor(insertUser("ADMIN"));

        mockMvc.perform(put("/admin/shops/{id}/status", Long.MAX_VALUE).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("enabled", 0))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/shops/{id}/status", shop.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("enabled", 2))))
                .andExpect(status().isBadRequest());
        assertEquals(1, shopMapper.selectById(shop.getId()).getEnabled());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_SHOP_REFRESH' AND aggregate_id = ?",
                Integer.class, shop.getId()));
    }

    @Test
    void statusUpdateRequiresAdmin() throws Exception {
        Shop shop = insertShop();
        String body = om.writeValueAsString(Map.of("enabled", 0));

        mockMvc.perform(put("/admin/shops/{id}/status", shop.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/shops/{id}/status", shop.getId())
                        .session(sessionFor(insertUser("MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(1, shopMapper.selectById(shop.getId()).getEnabled());
    }

    private Shop insertShop() {
        Shop shop = new Shop();
        shop.setOwnerUserId(insertUser("MERCHANT"));
        shop.setName("状态测试-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        return shop;
    }

    private Long insertUser(String role) {
        String username = "role_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private MockHttpSession sessionFor(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
