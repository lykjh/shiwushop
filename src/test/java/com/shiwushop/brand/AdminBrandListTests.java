package com.shiwushop.brand;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
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
class AdminBrandListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void adminListIncludesDisabledBrandAndMediaId() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "admin_" + suffix;
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = 'ADMIN' WHERE username = ?", username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        String storageKey = "test/brand-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), 'READY')
                """, userId, storageKey, storageKey);
        Long mediaId = jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, storageKey);
        String brandName = "测试品牌" + suffix;
        jdbcTemplate.update("INSERT INTO brands(name, logo_media_id, enabled) VALUES (?, ?, 0)",
                brandName, mediaId);
        Long brandId = jdbcTemplate.queryForObject(
                "SELECT id FROM brands WHERE name = ?", Long.class, brandName);

        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("userId", userId);
        mockMvc.perform(get("/admin/brands").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + brandId + ")].logoMediaId")
                        .value(hasItem(mediaId.intValue())))
                .andExpect(jsonPath("$.data[?(@.id == " + brandId + ")].enabled")
                        .value(hasItem(0)))
                .andExpect(jsonPath("$.data[?(@.id == " + brandId + ")].logoUrl").isEmpty());
    }
}
