package com.shiwushop.address;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.user.RegisterDTO;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class AddressCreateTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void encryptsAddressAndKeepsOneDefault() throws Exception {
        Long userId = user();
        MockHttpSession session = session(userId);
        long firstId = create(session, "第一条", 1);
        long secondId = create(session, "第二条", 1);

        byte[] ciphertext = jdbcTemplate.queryForObject(
                "SELECT consignee_ciphertext FROM addresses WHERE id = ?", byte[].class, firstId);
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("张三"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, firstId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, secondId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM addresses WHERE user_id = ? AND is_default = 1", Integer.class, userId));
    }

    @Test
    void rejectsOldFormatAndRequiresLogin() throws Exception {
        String valid = body("正常", 0);
        mockMvc.perform(post("/addresses").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/addresses").session(session(user()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("consignee", "张三", "phone", "13800138000",
                                "address", "旧版完整地址"))))
                .andExpect(status().isBadRequest());
    }

    private long create(MockHttpSession session, String detail, int isDefault) throws Exception {
        String response = mockMvc.perform(post("/addresses").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body(detail, isDefault)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consignee").value("张三"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.province").value("北京市"))
                .andExpect(jsonPath("$.data.detail").value(detail))
                .andExpect(jsonPath("$.data.address").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(response).path("data").path("id").asLong();
    }

    private String body(String detail, int isDefault) throws Exception {
        return om.writeValueAsString(Map.of("consignee", "张三", "phone", "13800138000",
                "province", "北京市", "city", "北京市", "district", "朝阳区",
                "detail", detail, "isDefault", isDefault));
    }

    private Long user() {
        String username = "address_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
