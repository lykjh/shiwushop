package com.shiwushop.shipment;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.entity.order.Order;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.entity.shipment.Shipment;
import com.shiwushop.entity.status.OrderStatus;
import com.shiwushop.entity.status.ShipmentStatus;
import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.mapper.shipment.ShipmentMapper;
import com.shiwushop.service.ShipmentService;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class ShipmentFlowTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private PrivacyCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopMapper shops;
    @Autowired private OrderMapper orders;
    @Autowired private ShipmentMapper shipments;
    @Autowired private OutboxEventMapper outbox;
    @Autowired private ShipmentService shipmentService;

    @Test
    void shipsOnceAndBuyerConfirmationIsIdempotent() throws Exception {
        Long merchant = user("MERCHANT");
        Long buyer = user("USER");
        Shop shop = shop(merchant);
        Order order = order(shop, buyer, OrderStatus.PAID);
        String path = "/merchant/shops/" + shop.getId() + "/orders/" + order.getId() + "/shipment";

        mvc.perform(get("/orders/{id}/shipment", order.getId()).session(session(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NOT_SHIPPED"));
        mvc.perform(post(path).session(session(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\" 顺丰速运 \",\"trackingNo\":\" SF123 \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.carrierName").value("顺丰速运"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF123"));
        mvc.perform(post(path).session(session(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"));
        mvc.perform(post(path).session(session(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"OTHER\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, order.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CREATED' AND target_id=?",
                Integer.class, shipments.selectByOrderId(order.getId()).getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Integer.class, shipments.selectByOrderId(order.getId()).getId()));
        Long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Long.class, shipments.selectByOrderId(order.getId()).getId());
        assertEquals(shipments.selectByOrderId(order.getId()).getId(),
                outbox.selectShipmentEvent(eventId).getShipmentId());

        mvc.perform(get(path).session(session(merchant))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED"));
        mvc.perform(post("/orders/{id}/shipment/confirm", order.getId()).session(session(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmSource").value("BUYER"));
        mvc.perform(post("/orders/{id}/shipment/confirm", order.getId()).session(session(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmSource").value("BUYER"));
        assertEquals(ShipmentStatus.CONFIRMED, shipments.selectByOrderId(order.getId()).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_BUYER' AND target_id=?",
                Integer.class, shipments.selectByOrderId(order.getId()).getId()));
        assertEquals(OrderStatus.PAID, orders.selectById(order.getId()).getStatus());
    }

    @Test
    void rejectsUnpaidForeignAndInvalidShipment() throws Exception {
        Long merchant = user("MERCHANT");
        Long outsider = user("MERCHANT");
        Long buyer = user("USER");
        Shop shop = shop(merchant);
        Order order = order(shop, buyer, OrderStatus.UNPAID);
        String path = "/merchant/shops/" + shop.getId() + "/orders/" + order.getId() + "/shipment";
        String body = "{\"carrierName\":\"顺丰\",\"trackingNo\":\"SF123\"}";

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).session(session(outsider)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).session(session(merchant)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post(path).session(session(merchant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"  \",\"trackingNo\":\"SF123\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/orders/{id}/shipment", order.getId()).session(session(merchant)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/orders/{id}/shipment/confirm", order.getId()).session(session(buyer)))
                .andExpect(status().isConflict());
    }

    @Test
    void dueShipmentConfirmsOnlyOnceAndKeepsPaymentStatus() throws Exception {
        Long merchant = user("MERCHANT");
        Long buyer = user("USER");
        Shop shop = shop(merchant);
        Order order = order(shop, buyer, OrderStatus.PAID);
        shipmentService.ship(merchant, shop.getId(), order.getId(),
                new com.shiwushop.dto.shipment.ShipmentCreateDTO("顺丰", "SF123"));
        Shipment shipment = shipments.selectByOrderId(order.getId());
        shipment.setShippedAt(LocalDateTime.now().minusDays(11));
        shipment.setAutoConfirmAt(LocalDateTime.now().minusDays(1));
        shipments.updateById(shipment);

        shipmentService.confirmDue(shipment.getId());
        shipmentService.confirmDue(shipment.getId());
        mvc.perform(post("/orders/{id}/shipment/confirm", order.getId()).session(session(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmSource").value("SYSTEM"));
        assertEquals(OrderStatus.PAID, orders.selectById(order.getId()).getStatus());
    }

    @Test
    void fullyRefundedOrderIsNotAutomaticallyConfirmed() {
        Long merchant = user("MERCHANT");
        Long buyer = user("USER");
        Shop shop = shop(merchant);
        Order order = order(shop, buyer, OrderStatus.PAID);
        shipmentService.ship(merchant, shop.getId(), order.getId(),
                new com.shiwushop.dto.shipment.ShipmentCreateDTO("顺丰", "SF123"));
        Shipment shipment = shipments.selectByOrderId(order.getId());
        shipment.setShippedAt(LocalDateTime.now().minusDays(11));
        shipment.setAutoConfirmAt(LocalDateTime.now().minusDays(1));
        shipments.updateById(shipment);

        String tradeNo = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO payment_attempts (order_id,channel,out_trade_no,app_id,seller_id,amount,status,paid_at) " +
                        "VALUES (?,'ALIPAY_SANDBOX',?,'app','seller',40,'SUCCESS',NOW())", order.getId(), tradeNo);
        Long paymentId = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE out_trade_no=?", Long.class, tradeNo);
        jdbc.update("INSERT INTO after_sale_cases (order_id,type,status,reason,refund_amount,requested_at) " +
                "VALUES (?,'REFUND_ONLY','REFUNDED','测试',40,NOW())", order.getId());
        Long caseId = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order.getId());
        jdbc.update("INSERT INTO refund_attempts (case_id,payment_attempt_id,out_request_no,amount,status,requested_at,resolved_at) " +
                "VALUES (?,?,?,40,'SUCCESS',NOW(),NOW())", caseId, paymentId, UUID.randomUUID().toString());

        assertFalse(shipmentService.dueShipmentIds().contains(shipment.getId()));
        shipmentService.confirmDue(shipment.getId());
        assertEquals(ShipmentStatus.SHIPPED, shipments.selectByOrderId(order.getId()).getStatus());
    }

    private Long user(String role) {
        String name = "shipment_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(name, "Shop123!", null));
        jdbc.update("UPDATE user_accounts SET role=? WHERE username=?", role, name);
        return jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, name);
    }

    private Shop shop(Long merchant) {
        Shop shop = new Shop();
        shop.setOwnerUserId(merchant);
        shop.setName("物流店铺-" + UUID.randomUUID());
        shop.setEnabled(0);
        shops.insert(shop);
        return shop;
    }

    private Order order(Shop shop, Long buyer, OrderStatus status) {
        Order order = new Order();
        order.setUserId(buyer);
        order.setShopId(shop.getId());
        order.setTotalAmount(new BigDecimal("40.00"));
        order.setConsigneeCiphertext(cipher.encrypt(buyer, "orders:consignee", "张三"));
        order.setPhoneCiphertext(cipher.encrypt(buyer, "orders:phone", "13800138000"));
        order.setDetailCiphertext(cipher.encrypt(buyer, "orders:detail", "下单地址"));
        order.setProvince("北京市");
        order.setCity("北京市");
        order.setDistrict("海淀区");
        order.setShippingKeyVersion(1);
        order.setPayExpiresAt(LocalDateTime.now().plusMinutes(15));
        if (status == OrderStatus.PAID) order.setPaidAt(LocalDateTime.now());
        order.setStatus(status);
        orders.insert(order);
        return order;
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }
}
