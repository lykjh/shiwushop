package com.shiwushop.shop;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantShopListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private RegisterService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopMapper shopMapper;

    @Test
    void listsOnlyOwnShopsIncludingPausedOnes() throws Exception {
        Long owner = user("MERCHANT");
        Shop open = shop(owner, 1);
        Shop paused = shop(owner, 0);
        Shop foreign = shop(user("MERCHANT"), 1);

        mockMvc.perform(get("/merchant/shops").session(session(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + open.getId() + ")].enabled").value(hasItem(1)))
                .andExpect(jsonPath("$.data[?(@.id == " + paused.getId() + ")].enabled").value(hasItem(0)))
                .andExpect(jsonPath("$.data[*].id").value(not(hasItem(foreign.getId().intValue()))));
    }

    @Test
    void requiresMerchantLogin() throws Exception {
        mockMvc.perform(get("/merchant/shops"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/merchant/shops").session(session(user("USER"))))
                .andExpect(status().isForbidden());
        Long disabled = user("MERCHANT");
        jdbcTemplate.update("UPDATE user_accounts SET status = 'DISABLED' WHERE user_id = ?", disabled);
        mockMvc.perform(get("/merchant/shops").session(session(disabled)))
                .andExpect(status().isUnauthorized());
    }

    private Long user(String role) {
        String username = "shop_list_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = ? WHERE username = ?", role, username);
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private Shop shop(Long owner, int enabled) {
        Shop shop = new Shop();
        shop.setOwnerUserId(owner);
        shop.setName("店铺-" + UUID.randomUUID());
        shop.setEnabled(enabled);
        shopMapper.insert(shop);
        return shop;
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
