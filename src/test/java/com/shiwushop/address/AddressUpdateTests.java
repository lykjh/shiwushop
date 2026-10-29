package com.shiwushop.address;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.vo.address.AddressVO;
import com.shiwushop.account.service.AddressService;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class AddressUpdateTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private AddressService addressService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void updatesEncryptedFieldsAndSwitchesDefault() throws Exception {
        Long userId = user();
        AddressSaveDTO firstDto = dto("第一条", 1);
        AddressVO first = addressService.create(userId, firstDto);
        AddressVO second = addressService.create(userId, dto("第二条", 0));
        AddressSaveDTO updated = dto("新地址", 1);

        mockMvc.perform(put("/addresses/{id}", second.getId()).session(session(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail").value("新地址"))
                .andExpect(jsonPath("$.data.isDefault").value(1));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, first.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT is_default FROM addresses WHERE id = ?", Integer.class, second.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT key_version FROM addresses WHERE id = ?", Integer.class, second.getId()));
    }

    @Test
    void refusesOtherOwnersDeletedAddressesAndOldFormat() throws Exception {
        Long ownerId = user();
        Long otherId = user();
        AddressVO address = addressService.create(ownerId, dto("原地址", 0));
        String body = om.writeValueAsString(dto("修改", 0));

        mockMvc.perform(put("/addresses/{id}", address.getId()).session(session(otherId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/addresses/{id}", address.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/addresses/{id}", address.getId()).session(session(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("consignee", "张三", "phone", "13800138000",
                                "address", "旧完整地址"))))
                .andExpect(status().isBadRequest());
        jdbcTemplate.update("UPDATE addresses SET deleted_at = NOW() WHERE id = ?", address.getId());
        mockMvc.perform(put("/addresses/{id}", address.getId()).session(session(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    private AddressSaveDTO dto(String detail, int isDefault) {
        AddressSaveDTO dto = new AddressSaveDTO();
        dto.setConsignee("张三");
        dto.setPhone("13800138000");
        dto.setProvince("北京市");
        dto.setCity("北京市");
        dto.setDistrict("朝阳区");
        dto.setDetail(detail);
        dto.setIsDefault(isDefault);
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
