package com.shiwushop.address;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.service.RegisterService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class AddressPreparationConcurrencyTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private RegisterService users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void fiftyBuyersCanPrepareAddressesAtOnce() throws Exception {
        List<Long> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                String name = "address_prepare_" + UUID.randomUUID().toString().replace("-", "");
                TestRegistration.register(users, registrationProofs, new RegisterDTO(name, "Shop123!", null));
                ids.add(jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, name));
            }
            CountDownLatch start = new CountDownLatch(1);
            String body = """
                    {"consignee":"测试用户","phone":"13800138000","province":"北京市",
                     "city":"北京市","district":"海淀区","detail":"测试路1号","isDefault":1}
                    """;
            try (var pool = Executors.newFixedThreadPool(50)) {
                var responses = ids.stream().map(id -> pool.submit(() -> {
                    start.await();
                    MockHttpSession session = new MockHttpSession();
                    session.setAttribute("userId", id);
                    return mvc.perform(post("/addresses").session(session)
                            .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
                })).toList();
                start.countDown();
                for (var response : responses) assertEquals(200, response.get(45, TimeUnit.SECONDS));
            }
            for (Long id : ids) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE user_id=?", Integer.class, id));
            }
        } finally {
            for (Long id : ids) {
                jdbc.update("DELETE FROM addresses WHERE user_id=?", id);
                jdbc.update("DELETE FROM user_accounts WHERE user_id=?", id);
                jdbc.update("DELETE FROM users WHERE id=?", id);
            }
        }
    }
}
