package com.shiwushop.brand;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.account.service.RegisterService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
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
class BrandUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;

    @Autowired private MockMvc mockMvc;
    @Autowired private BrandMapper brandMapper;
    @Autowired private RegisterService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void renamesAndDisablesBrand() throws Exception {
        Brand brand = insertBrand("旧品牌-" + UUID.randomUUID());
        String newName = "新品牌-" + UUID.randomUUID();
        MockHttpSession admin = sessionForRole("ADMIN");
        Long logoMediaId = mediaId(admin, "READY");

        mockMvc.perform(put("/admin/brands/{id}", brand.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", newName, "logoMediaId", logoMediaId, "enabled", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value(newName))
                .andExpect(jsonPath("$.data.logoMediaId").value(logoMediaId))
                .andExpect(jsonPath("$.data.logoUrl").doesNotExist())
                .andExpect(jsonPath("$.data.enabled").value(0));

        assertEquals(newName, brandMapper.selectById(brand.getId()).getName());
        assertEquals(0, brandMapper.selectById(brand.getId()).getEnabled());
        assertEquals(logoMediaId, brandMapper.selectById(brand.getId()).getLogoMediaId());
        assertEquals(1L, searchEvents(brand.getId()));

        mockMvc.perform(put("/admin/brands/{id}", brand.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", newName, "enabled", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logoMediaId").value(org.hamcrest.Matchers.nullValue()));
        assertNull(brandMapper.selectById(brand.getId()).getLogoMediaId());
        assertEquals(2L, searchEvents(brand.getId()));
    }

    @Test
    void rejectsDuplicateMissingAndInvalidUpdate() throws Exception {
        MockHttpSession admin = sessionForRole("ADMIN");
        Brand first = insertBrand("品牌甲-" + UUID.randomUUID());
        Brand second = insertBrand("品牌乙-" + UUID.randomUUID());
        String duplicate = om.writeValueAsString(Map.of("name", first.getName(), "enabled", 1));

        mockMvc.perform(put("/admin/brands/{id}", second.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/admin/brands/{id}", Long.MAX_VALUE).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/brands/{id}", second.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", " ", "enabled", 2))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/admin/brands/{id}", second.getId()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "旧地址", "logoUrl",
                                "https://example.com/logo.png", "enabled", 1))))
                .andExpect(status().isBadRequest());
        for (Long mediaId : new Long[]{mediaId(admin, "STAGED"),
                mediaId(sessionForRole("USER"), "READY"), Long.MAX_VALUE}) {
            mockMvc.perform(put("/admin/brands/{id}", second.getId()).session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "非法媒体-" + UUID.randomUUID(),
                                    "logoMediaId", mediaId, "enabled", 1))))
                    .andExpect(status().isBadRequest());
        }

        assertEquals(second.getName(), brandMapper.selectById(second.getId()).getName());
        assertEquals(0L, searchEvents(second.getId()));
    }

    @Test
    void updateRequiresAdmin() throws Exception {
        Brand brand = insertBrand("权限品牌-" + UUID.randomUUID());
        String body = om.writeValueAsString(Map.of("name", brand.getName(), "enabled", 0));

        mockMvc.perform(put("/admin/brands/{id}", brand.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/brands/{id}", brand.getId()).session(sessionForRole("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(1, brandMapper.selectById(brand.getId()).getEnabled());
    }

    private Brand insertBrand(String name) {
        Brand brand = new Brand();
        brand.setName(name);
        brand.setEnabled(1);
        brandMapper.insert(brand);
        return brand;
    }

    private Long searchEvents(Long brandId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='SEARCH_BRAND_REFRESH' AND aggregate_id=?", Long.class, brandId);
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
        String key = "test/brand-update-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets(uploaded_by_user_id, storage_key, mime_type,
                    byte_size, width, height, sha256, status)
                VALUES (?, ?, 'image/png', 10, 1, 1, UNHEX(SHA2(?, 256)), ?)
                """, session.getAttribute("userId"), key, key, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM media_assets WHERE storage_key = ?", Long.class, key);
    }
}
