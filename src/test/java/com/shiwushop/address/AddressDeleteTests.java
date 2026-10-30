package com.shiwushop.address;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.vo.address.AddressVO;
import com.shiwushop.account.service.AddressService;
import com.shiwushop.account.service.RegisterService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class AddressDeleteTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private AddressService addressService;
    @Autowired private RegisterService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void softDeletesDefaultAndHidesItFromList() throws Exception {
        Long userId = user();
        AddressVO address = addressService.create(userId, dto());

        mockMvc.perform(delete("/addresses/{id}", address.getId()).session(session(userId)))
                .andExpect(status().isOk());
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM addresses WHERE id = ?", java.time.LocalDateTime.class, address.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, address.getId()));
        mockMvc.perform(get("/addresses").session(session(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(delete("/addresses/{id}", address.getId()).session(session(userId)))
                .andExpect(status().isNotFound());
        AddressVO replacement = addressService.create(userId, dto());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, replacement.getId()));
    }

    @Test
    void rejectsOtherOwnerAndAnonymousDelete() throws Exception {
        Long ownerId = user();
        AddressVO address = addressService.create(ownerId, dto());
        mockMvc.perform(delete("/addresses/{id}", address.getId()).session(session(user())))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/addresses/{id}", address.getId()))
                .andExpect(status().isUnauthorized());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, address.getId()));
    }

    private AddressSaveDTO dto() {
        AddressSaveDTO dto = new AddressSaveDTO();
        dto.setConsignee("张三");
        dto.setPhone("13800138000");
        dto.setProvince("北京市");
        dto.setCity("北京市");
        dto.setDistrict("朝阳区");
        dto.setDetail("示例路1号");
        dto.setIsDefault(1);
        return dto;
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
