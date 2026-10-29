package com.shiwushop.order;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.entity.status.OrderStatus;
import com.shiwushop.entity.status.AccountStatus;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.entity.user.UserAccount;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.mapper.user.UserAccountMapper;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class OrderListTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private ShopMapper shopMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private PrivacyCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper accountMapper;
    @Autowired private StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void walksAllOrdersOnceWhenCreatedAtValuesTie() throws Exception {
        Long buyer = user();
        Shop shop = new Shop();
        shop.setName("游标店铺-" + UUID.randomUUID());
        shop.setOwnerUserId(buyer);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        List<Long> ids = new ArrayList<>();
        for (int n = 0; n < 23; n++) ids.add(order(buyer, shop.getId()).getId());
        jdbc.update("UPDATE orders SET created_at='2026-10-26 10:00:00' WHERE user_id=?", buyer);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", buyer);

        Long cursor = null;
        ArrayList<Long> seen = new ArrayList<>();
        while (true) {
            var request = get("/orders").param("size", "5").session(session);
            if (cursor != null) request.param("afterId", cursor.toString());
            var response = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
            var data = json.readTree(response.getResponse().getContentAsString()).path("data");
            for (var item : data.path("items")) seen.add(item.path("id").asLong());
            if (!data.path("hasMore").asBoolean()) break;
            cursor = data.path("nextCursor").asLong();
        }
        ids.sort(java.util.Comparator.reverseOrder());
        assertEquals(ids, seen);
        assertEquals(23, new HashSet<>(seen).size());
    }

    @Test
    void paginatesOnlyOwnOrdersAndRejectsDisabledAccount() throws Exception {
        Long buyer = user();
        Long other = user();
        Shop shop = new Shop();
        shop.setName("订单店铺-" + UUID.randomUUID());
        shop.setOwnerUserId(buyer);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Order older = order(buyer, shop.getId());
        Order newer = order(buyer, shop.getId());
        Order foreign = order(other, shop.getId());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", buyer);

        mockMvc.perform(get("/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/orders?size=1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.data.items[0].shopName").value(shop.getName()))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(newer.getId()));
        order(buyer, shop.getId());
        mockMvc.perform(get("/orders").param("afterId", newer.getId().toString())
                        .param("size", "1").session(session))
                .andExpect(jsonPath("$.data.items[0].id").value(older.getId()))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mockMvc.perform(get("/orders?page=0").session(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders?page=2").session(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders?includeTotal=true").session(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders").param("afterId", foreign.getId().toString()).session(session))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders?afterId=0").session(session)).andExpect(status().isBadRequest());
        UserAccount account = accountMapper.selectById(buyer);
        account.setStatus(AccountStatus.DISABLED);
        accountMapper.updateById(account);
        // Fixture SQL bypasses the service's cache invalidation.
        redis.delete("shiwushop:auth:state:" + buyer);
        mockMvc.perform(get("/orders").session(session)).andExpect(status().isUnauthorized());
    }

    private Long user() {
        String username = "order-list-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
    }

    private Order order(Long buyer, Long shopId) {
        Order order = new Order();
        order.setUserId(buyer);
        order.setShopId(shopId);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.UNPAID);
        order.setPayExpiresAt(LocalDateTime.now().plusMinutes(15));
        order.setConsigneeCiphertext(cipher.encrypt(buyer, "orders:consignee", "张三"));
        order.setPhoneCiphertext(cipher.encrypt(buyer, "orders:phone", "13800138000"));
        order.setProvince("上海市");
        order.setCity("上海市");
        order.setDistrict("浦东新区");
        order.setDetailCiphertext(cipher.encrypt(buyer, "orders:detail", "测试地址"));
        order.setShippingKeyVersion(1);
        orderMapper.insert(order);
        return order;
    }
}
