package com.shiwushop.address;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.address.AddressSaveDTO;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.vo.address.AddressVO;
import com.shiwushop.service.AddressService;
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

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class AddressListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private AddressService addressService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void listsOnlyActiveOwnAddressesWithPlaintextResponse() throws Exception {
        Long ownerId = user();
        Long otherId = user();
        AddressVO visible = addressService.create(ownerId, dto("可见地址"));
        AddressVO deleted = addressService.create(ownerId, dto("已删除地址"));
        addressService.create(otherId, dto("他人地址"));
        jdbcTemplate.update("UPDATE addresses SET deleted_at = NOW() WHERE id = ?", deleted.getId());

        mockMvc.perform(get("/addresses").session(session(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + visible.getId() + ")].consignee")
                        .value(hasItem("张三")))
                .andExpect(jsonPath("$.data[?(@.id == " + visible.getId() + ")].detail")
                        .value(hasItem("可见地址")))
                .andExpect(jsonPath("$.data[?(@.id == " + deleted.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void requiresActiveLogin() throws Exception {
        mockMvc.perform(get("/addresses").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
        Long userId = user();
        jdbcTemplate.update("UPDATE user_accounts SET status = 'DISABLED' WHERE user_id = ?", userId);
        mockMvc.perform(get("/addresses").session(session(userId)))
                .andExpect(status().isUnauthorized());
    }

    private AddressSaveDTO dto(String detail) {
        AddressSaveDTO dto = new AddressSaveDTO();
        dto.setConsignee("张三");
        dto.setPhone("13800138000");
        dto.setProvince("北京市");
        dto.setCity("北京市");
        dto.setDistrict("朝阳区");
        dto.setDetail(detail);
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
