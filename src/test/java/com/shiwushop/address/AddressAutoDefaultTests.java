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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@Transactional
class AddressAutoDefaultTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private AddressService addresses;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void choosesRecentlyUsedAddressWhenDefaultIsClearedAndNewestWhenNoUsageExists() {
        String username = "address_auto_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        AddressVO first = addresses.create(userId, dto("第一条", null));
        AddressVO second = addresses.create(userId, dto("第二条", 0));
        assertDefault(userId, first.getId());
        jdbc.update("UPDATE addresses SET last_used_at = '2026-01-01 00:00:00' WHERE id = ?", second.getId());

        AddressVO cleared = addresses.update(userId, first.getId(), dto("第一条", 0));
        assertEquals(0, cleared.getIsDefault());
        assertDefault(userId, second.getId());

        addresses.delete(userId, second.getId());
        assertDefault(userId, first.getId());

        AddressVO third = addresses.create(userId, dto("第三条", 0));
        addresses.delete(userId, first.getId());
        assertDefault(userId, third.getId());
    }

    private void assertDefault(Long userId, Long id) {
        assertEquals(id, jdbc.queryForObject("SELECT id FROM addresses WHERE user_id = ? AND is_default = 1 AND deleted_at IS NULL",
                Long.class, userId));
    }

    private AddressSaveDTO dto(String detail, Integer isDefault) {
        AddressSaveDTO dto = new AddressSaveDTO();
        dto.setConsignee("张三");
        dto.setPhone("13800138000");
        dto.setProvince("北京市");
        dto.setCity("北京市");
        dto.setDistrict("海淀区");
        dto.setDetail(detail);
        dto.setIsDefault(isDefault);
        return dto;
    }
}
