package com.shiwushop.address;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.address.AddressSaveDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.vo.address.AddressVO;
import com.shiwushop.account.service.AddressService;
import com.shiwushop.account.service.RegisterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class AddressDefaultConcurrencyTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private AddressService addresses;
    @Autowired private RegisterService users;
    @Autowired private JdbcTemplate jdbc;
    private Long userId;

    @Test
    void concurrentDefaultSwitchesLeaveExactlyOneDefault() throws Exception {
        String username = "address_parallel_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        AddressVO first = addresses.create(userId, address("甲", 0));
        AddressVO second = addresses.create(userId, address("乙", 0));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> { start.await(); return addresses.update(userId, first.getId(), address("甲", 1)); });
            var right = executor.submit(() -> { start.await(); return addresses.update(userId, second.getId(), address("乙", 1)); });
            start.countDown();
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE user_id=? " +
                "AND deleted_at IS NULL AND is_default=1", Integer.class, userId));
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) return;
        jdbc.update("DELETE FROM addresses WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
        jdbc.update("DELETE FROM users WHERE id=?", userId);
    }

    private static AddressSaveDTO address(String detail, int isDefault) {
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
