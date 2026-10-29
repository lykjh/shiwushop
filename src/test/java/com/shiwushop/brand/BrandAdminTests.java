package com.shiwushop.brand;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BrandAdminTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private BrandMapper brandMapper;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void createsBrandAndListsDisabledBrandForAdmin() throws Exception {
        MockHttpSession admin = sessionForRole("ADMIN");
        String name = "测试品牌-" + UUID.randomUUID();
        Long logoMediaId = mediaId(admin, "READY");
        MvcResult created = mockMvc.perform(post("/admin/brands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", name, "logoMediaId", logoMediaId, "enabled", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.logoMediaId").value(logoMediaId))
                .andExpect(jsonPath("$.data.logoUrl").doesNotExist())
                .andExpect(jsonPath("$.data.enabled").value(0))
                .andReturn();

        long id = om.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertEquals(name, brandMapper.selectById(id).getName());
        assertEquals(logoMediaId, brandMapper.selectById(id).getLogoMediaId());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='SEARCH_BRAND_REFRESH' AND aggregate_id=?", Long.class, id));

        mockMvc.perform(get("/admin/brands").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + id + ")].enabled").value(hasItem(0)));
    }

    @Test
    void rejectsDuplicateAndInvalidBrand() throws Exception {
        MockHttpSession admin = sessionForRole("ADMIN");
        String name = "测试品牌-" + UUID.randomUUID();
        String valid = om.writeValueAsString(Map.of("name", name, "enabled", 1));

        mockMvc.perform(post("/admin/brands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/brands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/admin/brands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", " ", "enabled", 2))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/admin/brands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "旧图片地址", "logoUrl",
                                "https://example.com/logo.png", "enabled", 1))))
                .andExpect(status().isBadRequest());

        for (Long mediaId : new Long[]{mediaId(admin, "STAGED"),
                mediaId(sessionForRole("USER"), "READY"), Long.MAX_VALUE}) {
            mockMvc.perform(post("/admin/brands").session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "不可用图片-" + UUID.randomUUID(),
                                    "logoMediaId", mediaId, "enabled", 1))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void brandManagementRequiresAdmin() throws Exception {
        String body = om.writeValueAsString(Map.of("name", "测试品牌-" + UUID.randomUUID(), "enabled", 1));

        mockMvc.perform(post("/admin/brands")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/brands").session(sessionForRole("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/brands"))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpSession sessionForRole(String role) {
        String username = "role_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }

    private Long mediaId(MockHttpSession session, String status) {
        String key = "test/brand-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), ?)
                """, session.getAttribute("userId"), key, key, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }
}
